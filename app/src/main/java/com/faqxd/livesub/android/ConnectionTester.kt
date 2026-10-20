package com.faqxd.livesub.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
        // Only the first verdict counts: closing or cancelling the socket
        // afterwards fires onClosing / onFailure, which must not turn a
        // successful setup into a "Socket closed" failure.
        val decided = AtomicBoolean(false)
        fun finish(verdict: Result) {
            if (decided.compareAndSet(false, true)) {
                result.set(verdict)
                latch.countDown()
            }
        }
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
                handleMessage(webSocket, text)
            }

            // Same JSON payload in a binary frame; GeminiClient accepts both.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(webSocket, bytes.utf8())
            }

            private fun handleMessage(webSocket: WebSocket, text: String) {
                // Malformed frames carry no verdict; keep waiting.
                val msg = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                when {
                    msg.has("setupComplete") -> {
                        finish(Result.Ok)
                        webSocket.close(1000, null)
                    }
                    msg.has("error") -> {
                        val m = msg.optJSONObject("error")?.optString("message", "unknown") ?: "unknown"
                        finish(Result.Failed(m))
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                val trimmed = reason.trim()
                finish(Result.Failed(if (trimmed.isEmpty()) "$code" else "$code $trimmed"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                finish(
                    Result.Failed(if (code != null) "HTTP $code" else t.message ?: t.javaClass.simpleName)
                )
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
