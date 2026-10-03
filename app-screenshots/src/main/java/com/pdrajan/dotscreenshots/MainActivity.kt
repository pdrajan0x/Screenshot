package com.pdrajan.dotscreenshots

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dotscreenshots.reminders.ReminderScheduler
import com.pdrajan.dotscreenshots.ui.DotScreenshotsNavHost
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Screenshot to open, e.g. from a reminder notification. */
    private val openShot = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DotScreenshotsApp).container
        handle(intent)
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            DotTheme(theme) {
                DotScreenshotsNavHost(container, openShot, onShotOpened = { openShot.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(ReminderScheduler.EXTRA_SHOT, -1L) ?: -1L
        if (id >= 0) openShot.value = id
    }
}
