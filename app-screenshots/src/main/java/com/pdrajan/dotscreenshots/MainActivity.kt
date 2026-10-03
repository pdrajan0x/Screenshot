package com.pdrajan.dotscreenshots

import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.CrashReportDialog
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dotscreenshots.ui.DotScreenshotsNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighRefreshRate()
        val container = (application as DotScreenshotsApp).container
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            DotTheme(theme) {
                DotScreenshotsNavHost(container)
                CrashReportDialog()
            }
        }
    }

    /**
     * Asks for the screen's fastest refresh rate (120 Hz on most newer phones) while the app is
     * open; some phones keep apps at 60 Hz unless they ask. Battery saver and the phone's own
     * refresh-rate setting still take priority.
     */
    private fun requestHighRefreshRate() {
        val screen: Display = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        }.getOrNull() ?: return
        val current = screen.mode
        val fastest = screen.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = fastest.modeId }
    }
}
