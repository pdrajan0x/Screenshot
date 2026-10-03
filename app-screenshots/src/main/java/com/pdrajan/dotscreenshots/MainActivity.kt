package com.pdrajan.dotscreenshots

import android.os.Bundle
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
        val container = (application as DotScreenshotsApp).container
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            DotTheme(theme) {
                DotScreenshotsNavHost(container)
                CrashReportDialog()
            }
        }
    }
}
