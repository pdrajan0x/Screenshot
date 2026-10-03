package com.pdrajan.dotgallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.CrashReportDialog
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dotgallery.ui.GalleryNavHost
import kotlinx.coroutines.flow.MutableStateFlow

/** FragmentActivity (not ComponentActivity) because the locked folder uses BiometricPrompt. */
class MainActivity : FragmentActivity() {

    /** A photo or video another app asked us to open ("Open with Dot Gallery"). */
    private val external = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GalleryApp).container
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            DotTheme(theme) {
                GalleryNavHost(container, external, onExternalHandled = { external.value = null })
                CrashReportDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { external.value = it }
    }
}
