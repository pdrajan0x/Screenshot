package com.pdrajan.dotscreenshots.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.dotGrid
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    var access by remember { mutableStateOf(MediaPermissions.access(context)) }
    var asked by remember { mutableStateOf(false) }

    fun finish() {
        container.settings.setOnboardingDone()
        container.onForeground()
        onDone()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = MediaPermissions.access(context)
        if (access == MediaAccess.FULL) finish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dotGrid(DotTheme.extra.dots),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(14.dp).clip(CircleShape).background(DotTheme.extra.accent))
            Spacer(Modifier.height(20.dp))
            Text("DOT\nSCREENSHOTS", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "Find any screenshot by what's written in it or what's in the picture.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Feature(Icons.Rounded.TravelExplore, "Search by words or by what you see", "\"car\", \"movie\", \"UPI\", a name, a price…")
            Feature(Icons.Rounded.Lock, "Everything stays on your phone", "No account, no cloud. Your screenshots never leave the phone.")
            Feature(Icons.Rounded.BatteryChargingFull, "Easy on the battery", "You choose when older screenshots are read: only while charging, or on battery above a level.")
            Spacer(Modifier.weight(1f))

            when {
                access == MediaAccess.PARTIAL -> {
                    Text(
                        "You chose “Select photos”, so only those screenshots are visible. Allow all photos to search every screenshot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    DotPrimaryButton("Allow all photos", onClick = { launcher.launch(MediaPermissions.required(includeVideo = false)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    DotOutlinedButton("Continue with selected", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                }
                asked && access == MediaAccess.NONE -> {
                    Text(
                        "Dot Screenshots needs access to your photos to find screenshots. Nothing leaves your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    DotPrimaryButton("Try again", onClick = { launcher.launch(MediaPermissions.required(includeVideo = false)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    DotOutlinedButton("Open app settings", onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }, modifier = Modifier.fillMaxWidth())
                }
                access == MediaAccess.FULL -> DotPrimaryButton("Get started", onClick = { finish() }, modifier = Modifier.fillMaxWidth(), accent = true)
                else -> DotPrimaryButton(
                    "Allow access to screenshots",
                    onClick = { launcher.launch(MediaPermissions.required(includeVideo = false)) },
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                )
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
