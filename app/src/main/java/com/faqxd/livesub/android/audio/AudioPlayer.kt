package com.faqxd.livesub.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of `audio.py:AudioPlayer`.
 *
 * Plays back the 24 kHz mono PCM16 audio that Gemini Live returns when
 * `echoTargetLanguage` is enabled. Uses [AudioTrack] in streaming mode
 * (write-blocking), with a background thread draining an internal buffer.
 *
 * Input is int16 LE (matches the Windows `enqueue_pcm16` API); we convert
 * to float on the fly because AudioTrack with ENCODING_PCM_FLOAT gives us
 * better volume control + avoids 16-bit clipping on modern Android.
 */
class AudioPlayer(
    private val sampleRate: Int = GEMINI_OUTPUT_RATE,
    @Volatile var gain: Float = 0.8f,
) {
    private val lock = Any()
    private var buffer = FloatArray(0)
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate / 5)  // at least 200 ms
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(gain.coerceIn(0f, 1f))
        t.play()
        track = t
        running = true
        thread = Thread({ drainLoop(t, minBuf) }, "AudioPlayer").apply {
            isDaemon = true
            start()
        }
    }

    /** Enqueue PCM16 LE bytes for playback. */
    fun enqueuePcm16(data: ByteArray) {
        if (data.size < 2) return
        // Drop odd trailing byte (defensive)
        val aligned = if (data.size % 2 != 0) data.copyOf(data.size - 1) else data
        val floats = FloatArray(aligned.size / 2)
        val sb = ByteBuffer
            .wrap(aligned)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        for (i in floats.indices) floats[i] = sb.get(i) / 32768f
        synchronized(lock) {
            buffer = if (buffer.isEmpty()) floats else buffer + floats
            // Cap buffer at 5 seconds to avoid runaway memory if AudioTrack stalls.
            val maxSamples = sampleRate * 5
            if (buffer.size > maxSamples) {
                buffer = buffer.copyOfRange(buffer.size - maxSamples, buffer.size)
            }
        }
    }

    fun setVolume(v: Float) {
        gain = v.coerceIn(0f, 1f)
        track?.setVolume(gain)
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        synchronized(lock) { buffer = FloatArray(0) }
    }

    // ---------- internals ----------

    private fun drainLoop(t: AudioTrack, bufSizeFrames: Int) {
        val out = FloatArray(bufSizeFrames)
        while (running) {
            val take: Int
            synchronized(lock) {
                take = minOf(bufSizeFrames, buffer.size)
                if (take > 0) {
                    System.arraycopy(buffer, 0, out, 0, take)
                    buffer = buffer.copyOfRange(take, buffer.size)
                }
            }
            if (take == 0) {
                // Buffer underrun: write silence to keep AudioTrack fed.
                java.util.Arrays.fill(out, 0f)
                t.write(out, 0, bufSizeFrames, AudioTrack.WRITE_BLOCKING)
            } else {
                if (take < bufSizeFrames) {
                    java.util.Arrays.fill(out, take, bufSizeFrames, 0f)
                }
                t.write(out, 0, bufSizeFrames, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    companion object {
        const val GEMINI_OUTPUT_RATE = 24000
    }
}
