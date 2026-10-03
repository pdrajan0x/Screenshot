package com.pdrajan.dotgallery.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Face
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
    val c = galleryContainer()
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(MediaPermissions.access(ctx)) }
    var asked by remember { mutableStateOf(false) }

    fun finish() {
        c.settings.setOnboarded()
        c.onForeground()
        onDone()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = MediaPermissions.access(ctx)
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
            Text("DOT\nGALLERY", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "Your photos and videos, organised and searchable — without an account or the cloud.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Feature(Icons.Rounded.TravelExplore, "Search by what's in the photo", "\"dog on beach\", \"red car\", \"receipt\", \"food last month\"")
            Feature(Icons.Rounded.Face, "People & pets", "Similar faces are grouped. Name them to search.")
            Feature(Icons.Rounded.AutoFixHigh, "Edit, trim, lock", "Crop, filters, markup, video trim and a locked folder.")
            Feature(Icons.Rounded.Lock, "Private by design", "AI runs on this phone. Nothing is uploaded.")
            Spacer(Modifier.weight(1f))

            when {
                access == MediaAccess.PARTIAL -> {
                    Text(
                        "You chose “Select photos”, so only those items are visible. Allow all to see your whole library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    DotPrimaryButton("Allow all photos and videos", onClick = { launcher.launch(galleryPermissions()) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    DotOutlinedButton("Continue with selected", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                }
                asked && access == MediaAccess.NONE -> {
                    Text(
                        "Dot Gallery needs access to your photos and videos to show them. Nothing leaves your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    DotPrimaryButton("Try again", onClick = { launcher.launch(galleryPermissions()) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    DotOutlinedButton("Open app settings", onClick = {
                        ctx.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    }, modifier = Modifier.fillMaxWidth())
                }
                access == MediaAccess.FULL -> DotPrimaryButton("Get started", onClick = { finish() }, modifier = Modifier.fillMaxWidth(), accent = true)
                else -> DotPrimaryButton(
                    "Allow access to photos",
                    onClick = { launcher.launch(galleryPermissions()) },
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                )
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
