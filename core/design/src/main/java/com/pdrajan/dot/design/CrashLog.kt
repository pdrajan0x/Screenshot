package com.pdrajan.dot.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Keeps the last crash on disk so it can be shown (and copied) the next time the app opens —
 * there's no cloud crash reporting by design.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val TAG = "Dot"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** For background work that failed without crashing: logged, never shown. */
    fun warn(error: Throwable) {
        Log.w(TAG, "Background work failed", error)
    }

    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }

    private fun report(context: Context, thread: String, error: Throwable): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("${context.packageName} $version")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: $thread")
            appendLine()
            append(error.stackTraceToString().take(12_000))
        }
    }
}

/** Shows the previous crash once, with a copy button. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.take(context)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("Sorry — the app crashed last time") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Copy this report and send it to whoever builds the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.heightIn(min = 40.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Crash report", text))
                report = null
            }) { Text("Copy report") }
        },
        dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
    )
}
