package com.faqxd.livesub.android.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.faqxd.livesub.android.subtitle.SubtitleCue
import com.faqxd.livesub.android.subtitle.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Subtitle files the user picks with the system file picker. */
object SubtitleFiles {

    /** Larger files are refused (subtitle files are rarely over a few hundred KB). */
    const val MAX_BYTES = 5 * 1024 * 1024

    class TooLargeException : IOException("larger than $MAX_BYTES bytes")

    /**
     * The cues of the subtitle file at [uri], empty if it has none. Throws
     * if it can't be read or is larger than [MAX_BYTES]. Blocking I/O.
     */
    fun read(context: Context, uri: Uri): List<SubtitleCue> {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open $uri")
        val bytes = input.use { readAtMost(it, MAX_BYTES) }
        return SubtitleParser.parse(bytes)
    }

    /** The file's name without its extension, to title the playback. Blocking I/O. */
    fun title(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        return (name ?: uri.lastPathSegment ?: "").substringBeforeLast('.')
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw TooLargeException()
        }
        return out.toByteArray()
    }
}
