package com.faqxd.livesub.android.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of `audio.py:AudioCapture`.
 *
 * Captures microphone or system/loopback audio and forwards fixed-size
 * 16 kHz mono PCM16 chunks (3200 bytes ≈ 100 ms) to [onChunk] — the exact
 * format Gemini Live expects.
 *
 * Microphone path notes
 * ---------------------
 * The previous implementation requested 16 kHz natively from `AudioRecord`.
 * On paper that should work (16 kHz is a required Android sample rate), but
 * in practice many OEM ROMs — especially Chinese custom ROMs (MIUI,
 * ColorOS, OriginOS, HarmonyOS, ...) — silently downmix or AGC-clamp the
 * `VOICE_RECOGNITION` source at non-native rates, returning near-silent
 * samples that Gemini cannot transcribe.
 *
 * The robust fix is to capture at the device's native output sample rate
 * (typically 48 kHz mono) — the same path the system-audio branch already
 * uses — and resample to 16 kHz via [PCM16Downsampler]. We also try
 * `AudioSource.MIC` first (most portable) and fall back to
 * `AudioSource.VOICE_RECOGNITION` if `MIC` fails to initialize.
 *
 * System audio path
 * -----------------
 * Built by the caller from a `MediaProjection` token and pushed in via
 * [startSystemAudio]; this class only owns the read loop.
 */
class AudioCapture(
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val downsampler = PCM16Downsampler(targetRate = GEMINI_INPUT_RATE)
    private val chunker = PCM16Chunker(chunkSize = CHUNK_SIZE, onChunk = onChunk)

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var running = false
    private var sampleRate = 0
    private var channels = 1

    /**
     * Start microphone capture.
     *
     * Captures at the device's native output sample rate (usually 48 kHz)
     * and resamples to 16 kHz before forwarding. Requires `RECORD_AUDIO`
     * permission; the calling service MUST hold a foreground service of
     * type `microphone` (Android 14+).
     *
     * @param context used to query the native output sample rate. If null,
     *                falls back to 48 kHz.
     */
    @SuppressLint("MissingPermission")
    fun startMicrophone(context: Context? = null) {
        if (running) return

        val nativeRate = nativeOutputSampleRate(context)
        val srcChannels = AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(
            nativeRate,
            srcChannels,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(nativeRate / 5 * 2)  // ≥ 200ms at srcRate mono int16

        // Try MIC first (most portable across OEM ROMs); fall back to
        // VOICE_RECOGNITION if MIC initialization fails. Keep VOICE_COMMUNICATION
        // as a last resort (it applies platform AGC/NS which can hurt Gemini).
        val ar = buildMicRecord(nativeRate, srcChannels, minBuf, preferredSource = MediaRecorder.AudioSource.MIC)
            ?: buildMicRecord(nativeRate, srcChannels, minBuf, preferredSource = MediaRecorder.AudioSource.VOICE_RECOGNITION)
            ?: buildMicRecord(nativeRate, srcChannels, minBuf, preferredSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: throw RuntimeException(
                "AudioRecord failed to initialize at ${nativeRate}Hz mono PCM16 " +
                    "(tried MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION)"
            )

        record = ar
        sampleRate = nativeRate
        channels = 1
        running = true
        ar.startRecording()
        Log.i(
            TAG,
            "Microphone capture started: rate=$nativeRate ch=1 source=${ar.audioSource} buf=$minBuf"
        )
        captureThread = Thread({ micLoop(ar, minBuf) }, "AudioCapture-mic").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Start system-audio (loopback) capture using a pre-configured
     * [AudioRecord] obtained from a MediaProjection's
     * `AudioPlaybackCaptureConfiguration`.
     *
     * The caller is responsible for building the AudioRecord (because it
     * needs the MediaProjection token, which we don't have here).
     */
    fun startSystemAudio(record: AudioRecord, srcRate: Int, srcChannels: Int) {
        if (running) return
        this.record = record
        this.sampleRate = srcRate
        this.channels = srcChannels
        running = true
        record.startRecording()
        val bufSize = AudioRecord.getMinBufferSize(
            srcRate,
            if (srcChannels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(CHUNK_SIZE * 2)
        Log.i(TAG, "System audio capture started: rate=$srcRate ch=$srcChannels buf=$bufSize")
        captureThread = Thread({ systemLoop(record, bufSize) }, "AudioCapture-sys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        captureThread?.join(500)
        captureThread = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        downsampler.reset()
        chunker.reset()
    }

    // ---------- internals ----------

    private fun buildMicRecord(
        rate: Int,
        channelMask: Int,
        bufSize: Int,
        preferredSource: Int,
    ): AudioRecord? {
        return try {
            val ar = AudioRecord(
                preferredSource,
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2,
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized: source=$preferredSource state=${ar.state}")
                ar.release()
                null
            } else {
                ar
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord build failed: source=$preferredSource err=${e.message}")
            null
        }
    }

    private fun nativeOutputSampleRate(context: Context?): Int {
        if (context != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val r = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (r != null && r > 0) return r
        }
        // 48 kHz is the most common native rate on modern Android devices.
        return 48000
    }

    private fun micLoop(ar: AudioRecord, bufSize: Int) {
        // Capture at native rate (e.g. 48 kHz), then downsample to 16 kHz
        // before forwarding. Same pipeline as the system-audio branch.
        val buf = ByteArray(bufSize)
        val floats = FloatArray(bufSize / 2)
        val shortBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var silentChunks = 0
        var totalRead = 0L
        var errorStreak = 0
        var failure: String? = null
        while (running) {
            val read = ar.read(buf, 0, buf.size)
            if (read <= 0) {
                errorStreak++
                when (read) {
                    AudioRecord.ERROR_INVALID_OPERATION,
                    AudioRecord.ERROR_BAD_VALUE -> {
                        Log.w(TAG, "micLoop: fatal read error $read, stopping")
                        failure = "Microphone read failed ($read)"
                        break
                    }
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "micLoop: ERROR_DEAD_OBJECT — caller must restart capture")
                        failure = "Microphone recorder was disconnected"
                        break
                    }
                    else -> {
                        // ERROR or ERROR_TIMEOUT: tolerate a few times then bail
                        if (errorStreak > 50) {
                            Log.w(TAG, "micLoop: too many transient read errors ($errorStreak), stopping")
                            failure = "Microphone read timed out repeatedly"
                            break
                        }
                        Thread.sleep(5)
                        continue
                    }
                }
            } else {
                errorStreak = 0
                totalRead += read
                // Quick silence detection (RMS of PCM16 samples). Useful for
                // diagnosing "no transcription" — logs whether the mic is
                // actually delivering audio vs. feeding zeros.
                val rms = computePcm16Rms(buf, 0, read)
                if (rms < SILENCE_RMS_THRESHOLD) {
                    silentChunks++
                    if (silentChunks == LOG_SILENCE_AFTER) {
                        Log.w(TAG, "micLoop: $silentChunks silent chunks in a row (rms=$rms). Mic may be muted or wrong source.")
                    }
                } else {
                    if (silentChunks >= LOG_SILENCE_AFTER) {
                        Log.i(TAG, "micLoop: audio resumed after $silentChunks silent chunks (rms=$rms)")
                    }
                    silentChunks = 0
                }

                // PCM16 LE bytes → Float32 → downsampler → 16 kHz mono PCM16 LE
                val frames = read / 2  // mono: 1 sample = 2 bytes
                for (i in 0 until frames) {
                    floats[i] = shortBuffer.get(i) / 32768f
                }
                val input = if (frames == floats.size) floats else floats.copyOf(frames)
                val pcm16 = downsampler.convert(input, sampleRate, channels = 1)
                chunker.append(pcm16)

                // Periodic heartbeat so the user can see in logcat that
                // audio is flowing even when Gemini returns nothing.
                if (totalRead % (sampleRate * 4) < bufSize) {
                    Log.d(TAG, "micLoop heartbeat: totalRead=$totalRead rms=$rms")
                }
            }
        }
        failure?.let { reason ->
            if (running) {
                running = false
                onError(reason)
            }
        }
        Log.i(TAG, "micLoop exited: totalRead=$totalRead")
    }

    private fun systemLoop(ar: AudioRecord, bufSize: Int) {
        val buf = ByteArray(bufSize)
        val floats = FloatArray(bufSize / 2)
        val shortBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var silentChunks = 0
        var totalRead = 0L
        var errorStreak = 0
        var failure: String? = null
        while (running) {
            val read = ar.read(buf, 0, buf.size)
            if (read <= 0) {
                errorStreak++
                when (read) {
                    AudioRecord.ERROR_INVALID_OPERATION,
                    AudioRecord.ERROR_BAD_VALUE -> {
                        Log.w(TAG, "systemLoop: fatal read error $read, stopping")
                        failure = "System audio read failed ($read)"
                        break
                    }
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "systemLoop: ERROR_DEAD_OBJECT — caller must restart capture")
                        failure = "System audio recorder was disconnected"
                        break
                    }
                    else -> {
                        if (errorStreak > 50) {
                            Log.w(TAG, "systemLoop: too many transient read errors ($errorStreak), stopping")
                            failure = "System audio read timed out repeatedly"
                            break
                        }
                        Thread.sleep(5)
                        continue
                    }
                }
            }
            errorStreak = 0
            totalRead += read
            val rms = computePcm16Rms(buf, 0, read)
            if (rms < SILENCE_RMS_THRESHOLD) {
                silentChunks++
                if (silentChunks == LOG_SILENCE_AFTER) {
                    Log.w(TAG, "systemLoop: $silentChunks silent chunks in a row (rms=$rms). App may block playback capture or be silent.")
                }
            } else {
                if (silentChunks >= LOG_SILENCE_AFTER) {
                    Log.i(TAG, "systemLoop: audio resumed after $silentChunks silent chunks (rms=$rms)")
                }
                silentChunks = 0
            }
            // PCM16 LE bytes → Float32 array
            val frames = read / 2 / channels
            val sampleCount = frames * channels
            for (i in 0 until sampleCount) {
                floats[i] = shortBuffer.get(i) / 32768f
            }
            val input = if (sampleCount == floats.size) floats else floats.copyOf(sampleCount)
            val pcm16 = downsampler.convert(input, sampleRate, channels)
            chunker.append(pcm16)

            if (totalRead % (sampleRate * channels * 4) < bufSize) {
                Log.d(TAG, "systemLoop heartbeat: totalRead=$totalRead rms=$rms")
            }
        }
        failure?.let { reason ->
            if (running) {
                running = false
                onError(reason)
            }
        }
        Log.i(TAG, "systemLoop exited: totalRead=$totalRead")
    }

    private fun computePcm16Rms(buf: ByteArray, offset: Int, len: Int): Double {
        if (len < 2) return 0.0
        val sb = ByteBuffer.wrap(buf, offset, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = sb.remaining()
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = sb.get(i).toDouble() / 32768.0
            sumSq += v * v
        }
        return Math.sqrt(sumSq / n)
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val GEMINI_INPUT_RATE = 16000
        const val CHUNK_SIZE = 3200  // bytes per Gemini send (~100 ms @16kHz mono int16)
        private const val SILENCE_RMS_THRESHOLD = 0.005  // ~-46 dBFS; below this we call it silent
        private const val LOG_SILENCE_AFTER = 30  // ~3s of silent 100ms chunks before we warn
    }
}
