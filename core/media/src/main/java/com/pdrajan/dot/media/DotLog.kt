package com.pdrajan.dot.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log of what indexing did and why it failed. There is no cloud reporting, so
 * this is what the user can copy or save to Downloads (Settings → Diagnostics) and send along.
 */
object DotLog {
    private const val TAG = "Dot"
    private const val FILE = "dot-log.txt"
    private const val MAX_BYTES = 400_000L

    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    fun i(message: String) = write(Log.INFO, 'I', message, null)
    fun w(message: String, error: Throwable? = null) = write(Log.WARN, 'W', message, error)
    fun e(message: String, error: Throwable? = null) = write(Log.ERROR, 'E', message, error)

    @Synchronized
    private fun write(priority: Int, level: Char, message: String, error: Throwable?) {
        val trace = error?.stackTraceToString()?.lineSequence()?.take(40)?.joinToString("\n")
        Log.println(priority, TAG, if (trace != null) "$message\n$trace" else message)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()).substringAfter('\n'))
            f.appendText(buildString {
                append(time.format(Date())).append(' ').append(level).append(' ').append(message).append('\n')
                if (trace != null) append(trace).append('\n')
            })
        }
    }

    fun read(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        file?.delete()
    }

    /** Copies the log to the public Downloads folder; returns where it went, or null. */
    fun saveToDownloads(context: Context, displayName: String): String? = runCatching {
        val bytes = read().toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, displayName).writeBytes(bytes)
        }
        "Downloads/$displayName"
    }.getOrNull()
}
