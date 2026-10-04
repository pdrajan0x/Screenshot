package com.pdrajan.dot.media

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Earlier versions downloaded an AI model (up to 1.3 GB) into Download/AI Models or app storage.
 * Dot Screenshots no longer uses one; Settings offers to delete the files this app may delete.
 * Only these exact file names are touched.
 */
object OldModelFiles {

    private val NAMES = listOf(
        "LFM2.5-VL-1.6B-Q4_0.gguf",
        "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        "LFM2.5-VL-450M-Q4_0.gguf",
        "mmproj-LFM2.5-VL-450m-Q8_0.gguf",
        "Qwen3-1.7B-Q4_0.gguf",
    )

    private fun dirs(context: Context): List<File> = buildList {
        add(File(context.filesDir, "models"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AI Models"))
        }
    }

    /** Old model files this app may delete (its own downloads), with interrupted ones. */
    fun found(context: Context): List<File> = dirs(context).flatMap { dir ->
        NAMES.flatMap { listOf(File(dir, it), File(dir, "$it.part")) }
    }.filter { runCatching { it.isFile && it.canWrite() }.getOrDefault(false) }

    /** Deletes them; returns the bytes freed. */
    fun delete(context: Context): Long {
        var freed = 0L
        for (f in found(context)) {
            val size = runCatching { f.length() }.getOrDefault(0L)
            if (runCatching { f.delete() }.getOrDefault(false)) freed += size
        }
        // The shared folder goes too when nothing else is in it.
        for (dir in dirs(context)) runCatching { if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete() }
        DotLog.i("cleanup: removed old AI model files (${freed / 1_000_000} MB)")
        return freed
    }
}
