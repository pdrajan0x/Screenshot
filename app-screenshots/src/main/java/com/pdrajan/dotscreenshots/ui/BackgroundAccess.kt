package com.pdrajan.dotscreenshots.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Android's battery exemption. With it, reading and describing carry on after the app is closed;
 * without it Android pauses them soon after. Apps can't turn it on themselves: Android shows its
 * own one-tap prompt, which the app asks for once, on first launch ([AskForBackgroundOnce]).
 */
object BackgroundAccess {
    fun granted(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)

    /** Android's "Let Dot Screenshots always run in the background?" prompt. */
    @SuppressLint("BatteryLife")
    fun prompt(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    /** App info, where Battery → "Unrestricted" does the same (for phones without the prompt). */
    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}

/** Shows Android's background prompt once, shortly after the home screen first appears. */
@Composable
fun AskForBackgroundOnce() {
    val c = appContainer()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    LaunchedEffect(Unit) {
        if (c.settings.backgroundAsked || !c.settings.processing.value.background || BackgroundAccess.granted(context)) return@LaunchedEffect
        // Let the home screen settle first (right after the photos permission on a fresh install).
        delay(700)
        c.settings.backgroundAsked = true
        runCatching { launcher.launch(BackgroundAccess.prompt(context)) }
    }
}
