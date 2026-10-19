package com.faqxd.livesub.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot connectivity probe for the settings screen: opens the same
 * Live WebSocket endpoint the pipeline uses, sends the translate setup and
 * classifies the outcome (setupComplete / server error / close code /
 * transport failure). Blocking; run it off the main thread.
 */
object ConnectionTester {

    sealed class Result {
        object Ok : Result()
        data class Failed(val detail: String) : Result()
    }

    fun test(apiKey: String, apiBase: String, model: String): Result {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>(Result.Failed("timeout"))
        val base = apiBase.trim().trimEnd('/').let {
            when {
                it.startsWith("https://") -> "wss://" + it.removePrefix("https://")
                it.startsWith("http://") -> "ws://" + it.removePrefix("http://")
                else -> it
            }
        }
        val request = Request.Builder()
            .url("$base/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .header("x-goog-api-key", apiKey)
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val modelName = if (model.startsWith("models/")) model else "models/$model"
                val setup = JSONObject().put(
                    "setup",
                    JSONObject().apply {
                        put("model", modelName)
                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put("responseModalities", JSONArray().put("AUDIO"))
                                put(
                                    "translationConfig",
                                    JSONObject().put("targetLanguageCode", "en"),
                                )
                            },
                        )
                    },
                )
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when {
                    msg.has("setupComplete") -> {
                        result.set(Result.Ok)
                        latch.countDown()
                        webSocket.close(1000, null)
                    }
                    msg.has("error") -> {
                        val m = msg.getJSONObject("error").optString("message", "unknown")
                        result.set(Result.Failed(m))
                        latch.countDown()
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (result.get() is Result.Ok) {
                    latch.countDown()
                    return
                }
                val trimmed = reason.trim()
                result.set(Result.Failed(if (trimmed.isEmpty()) "$code" else "$code $trimmed"))
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                result.set(
                    Result.Failed(if (code != null) "HTTP $code" else t.message ?: t.javaClass.simpleName)
                )
                latch.countDown()
            }
        })

        latch.await(20, TimeUnit.SECONDS)
        ws.cancel()
        // Drop pooled keep-alive sockets immediately instead of waiting for
        // the idle timeout — this client is single-shot.
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        return result.get()
    }
}
