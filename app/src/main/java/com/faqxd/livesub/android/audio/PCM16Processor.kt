package com.faqxd.livesub.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `pcm_processor.py:PCM16Downsampler`.
 *
 * Convert multi-channel Float32 PCM at an arbitrary sample rate to mono
 * 16 kHz PCM16 little-endian bytes, suitable for Gemini Live's
 * `audio/pcm;rate=16000` realtime input.
 *
 * Uses linear interpolation (same as the Python implementation); Android's
 * AudioRecord normally hands us 16 kHz mono already when we request it, so
 * in practice the resampler is rarely exercised — but we keep it for parity
 * with the Windows / macOS versions that capture at the device's native rate.
 */
class PCM16Downsampler(private val targetRate: Int = 16000) {

    private var lastSrcRate = 0
    private var lastChannels = 0
    private var nextSourceOffset = 0.0
    private var tailSample: Float? = null

    /**
     * @param samples Float32 PCM samples, shape (frames,) or (frames, channels)
     *        flattened row-major (i.e. `samples[frame * channels + ch]`).
     * @param srcRate Native sample rate of the input.
     * @param channels Number of interleaved channels in `samples`.
     * @return Mono 16 kHz PCM16 bytes (little-endian).
     */
    fun convert(samples: FloatArray, srcRate: Int, channels: Int = 1): ByteArray {
        if (samples.isEmpty() || srcRate <= 0 || channels <= 0) return ByteArray(0)
        if (srcRate != lastSrcRate || channels != lastChannels) {
            reset()
            lastSrcRate = srcRate
            lastChannels = channels
        }

        // Downmix to mono
        val mono: FloatArray = if (channels == 1) {
            samples
        } else {
            val frameCount = samples.size / channels
            FloatArray(frameCount) { f ->
                var sum = 0f
                for (ch in 0 until channels) sum += samples[f * channels + ch]
                sum / channels
            }
        }
        if (mono.isEmpty()) return ByteArray(0)

        // Resample (linear interpolation)
        val resampled: FloatArray = if (srcRate == targetRate) {
            nextSourceOffset = 0.0
            tailSample = mono.lastOrNull()
            mono
        } else {
            resampleContinuous(mono, srcRate)
        }

        // Clip + convert to int16 LE
        val out = ByteArray(resampled.size * 2)
        val shortBuf: ShortBuffer =
            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in resampled.indices) {
            val v = resampled[i]
            val clamped = when {
                v > 1f -> 1f
                v < -1f -> -1f
                else -> v
            }
            shortBuf.put((clamped * 32767f).toInt().toShort())
        }
        return out
    }

    fun reset() {
        nextSourceOffset = 0.0
        tailSample = null
        lastSrcRate = 0
        lastChannels = 0
    }

    /** Convenience: decode raw Float32 little-endian bytes first. */
    fun convertBytes(float32Le: ByteArray, srcRate: Int, channels: Int = 1): ByteArray {
        if (float32Le.size % 4 != 0) return ByteArray(0)
        val floats = FloatArray(float32Le.size / 4)
        val buf = ByteBuffer.wrap(float32Le).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        buf.get(floats)
        return convert(floats, srcRate, channels)
    }

    private fun resampleContinuous(mono: FloatArray, srcRate: Int): FloatArray {
        val previous = tailSample
        val hasTail = previous != null
        val combined = if (hasTail) {
            FloatArray(mono.size + 1).also {
                it[0] = previous!!
                System.arraycopy(mono, 0, it, 1, mono.size)
            }
        } else {
            mono
        }
        val tailOffset = if (hasTail) 1.0 else 0.0
        val step = srcRate.toDouble() / targetRate.toDouble()
        var srcPos = nextSourceOffset + tailOffset
        val out = ArrayList<Float>(
            max(1, (mono.size * targetRate.toDouble() / srcRate.toDouble()).toInt() + 2)
        )

        // Stop before the last available frame so interpolation can use the
        // next frame. If the next frame belongs to a future buffer, the
        // fractional position is carried forward and resolved on the next call.
        val maxInterpolablePos = combined.size - 1
        while (srcPos < maxInterpolablePos) {
            val lower = min(combined.size - 1, max(0, floor(srcPos).toInt()))
            val upper = min(combined.size - 1, lower + 1)
            val frac = (srcPos - lower).toFloat()
            out.add(combined[lower] + (combined[upper] - combined[lower]) * frac)
            srcPos += step
        }

        nextSourceOffset = srcPos - (tailOffset + mono.size)
        tailSample = mono.lastOrNull()
        return out.toFloatArray()
    }
}

/**
 * Port of `pcm_processor.py:PCM16Chunker`.
 *
 * Accumulate PCM16 bytes and emit fixed-size chunks (default 3200 bytes =
 * 100 ms of 16 kHz mono int16). Thread-safe.
 *
 * Uses a single growable buffer with [System.arraycopy] compaction instead
 * of `ByteArray + ByteArray` concatenation — avoids the O(n²) copying that
 * the naive approach caused on every append.
 */
class PCM16Chunker(
    private val chunkSize: Int = 3200,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private var buffer = ByteArray(chunkSize * 2)
    private var size = 0

    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        val chunks = ArrayList<ByteArray>()
        synchronized(lock) {
            // Ensure capacity for the new data.
            val needed = size + data.size
            if (needed > buffer.size) {
                buffer = buffer.copyOf(needed.coerceAtLeast(buffer.size * 2))
            }
            System.arraycopy(data, 0, buffer, size, data.size)
            size += data.size

            // Emit complete chunks.
            var offset = 0
            while (size - offset >= chunkSize) {
                chunks.add(buffer.copyOfRange(offset, offset + chunkSize))
                offset += chunkSize
            }
            // Compact: move leftover to the front of the buffer.
            if (offset > 0) {
                val leftover = size - offset
                if (leftover > 0) {
                    System.arraycopy(buffer, offset, buffer, 0, leftover)
                }
                size = leftover
            }
        }
        for (chunk in chunks) onChunk(chunk)
    }

    fun reset() {
        synchronized(lock) {
            size = 0
        }
    }
}
