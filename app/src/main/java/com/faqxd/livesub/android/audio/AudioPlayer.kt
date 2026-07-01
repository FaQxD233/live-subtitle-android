package com.faqxd.livesub.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    // Chunk-based queue — avoids the O(n²) copying that FloatArray
    // concatenation caused on every enqueuePcm16 call.
    private val chunkQueue = ArrayDeque<FloatArray>()
    private var headOffset = 0   // read position within chunkQueue.first()
    private var queuedSamples = 0
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
        lock.withLock {
            chunkQueue.addLast(floats)
            queuedSamples += floats.size
            // Cap buffer at 5 seconds to avoid runaway memory if AudioTrack
            // stalls. Drop oldest chunks first.
            val maxSamples = sampleRate * 5
            while (queuedSamples > maxSamples && chunkQueue.isNotEmpty()) {
                val removed = chunkQueue.removeFirst()
                queuedSamples -= (removed.size - headOffset)
                headOffset = 0
            }
            notEmpty.signalAll()
        }
    }

    fun setVolume(v: Float) {
        gain = v.coerceIn(0f, 1f)
        track?.setVolume(gain)
    }

    fun stop() {
        running = false
        lock.withLock {
            notEmpty.signalAll()
        }
        thread?.join(500)
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        lock.withLock {
            chunkQueue.clear()
            headOffset = 0
            queuedSamples = 0
        }
    }

    // ---------- internals ----------

    private fun drainLoop(t: AudioTrack, bufSizeFrames: Int) {
        val out = FloatArray(bufSizeFrames)
        while (running) {
            var written = 0
            var pauseForIdle = false
            lock.withLock {
                if (chunkQueue.isEmpty()) {
                    notEmpty.await(IDLE_PAUSE_DELAY_MS, TimeUnit.MILLISECONDS)
                    if (!running) return
                    if (chunkQueue.isEmpty()) {
                        pauseForIdle = true
                    }
                }
                while (written < bufSizeFrames && chunkQueue.isNotEmpty()) {
                    val chunk = chunkQueue.peekFirst()
                    val available = chunk.size - headOffset
                    val toTake = minOf(available, bufSizeFrames - written)
                    System.arraycopy(chunk, headOffset, out, written, toTake)
                    written += toTake
                    headOffset += toTake
                    queuedSamples -= toTake
                    if (headOffset >= chunk.size) {
                        chunkQueue.removeFirst()
                        headOffset = 0
                    }
                }
            }
            if (pauseForIdle) {
                try {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                } catch (_: Exception) {}
                continue
            }
            if (written > 0) {
                try {
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                } catch (_: Exception) {}
                t.write(out, 0, written, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    companion object {
        const val GEMINI_OUTPUT_RATE = 24000
        private const val IDLE_PAUSE_DELAY_MS = 200L
    }
}
