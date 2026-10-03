package com.pdrajan.dotgallery.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.dotGrid
import com.pdrajan.dotgallery.data.LockedItem
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun authenticate(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Locked folder")
        .setSubtitle("Use your screen lock to open")
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

/**
 * Locked folder: hidden from other apps and the timeline, opens with the phone's screen lock,
 * blocks screenshots/recents previews, and locks again as soon as the app leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedScreen(nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val scope = rememberCoroutineScope()
    val canLock = remember { BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS }
    var unlocked by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf<List<LockedItem>?>(null) }
    val items by remember { c.repo.observeLocked() }.collectAsStateWithLifecycle(emptyList())

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        unlocked = false
        viewing = null
        selection = emptySet()
    }
    LaunchedEffect(Unit) {
        if (canLock && activity != null) authenticate(activity) { ok -> unlocked = ok }
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    fun restore(list: List<LockedItem>) {
        scope.launch {
            val restored = list.count { c.locked.restore(it) }
            selection = emptySet()
            viewing = null
            c.refresh()
            ctx.toast(if (restored == list.size) "Moved out of Locked folder" else "Couldn't move $restored of ${list.size}")
        }
    }

    if (!unlocked) {
        LockedGate(
            canLock = canLock,
            onUnlock = { if (activity != null) authenticate(activity) { ok -> unlocked = ok } },
            onSetUp = { ctx.startSafely(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
            onBack = { nav.back() },
        )
        return
    }

    val open = viewing
    if (open != null && items.isNotEmpty()) {
        LockedViewer(
            items = items,
            initial = open.coerceIn(0, items.lastIndex),
            onBack = { viewing = null },
            onRestore = { restore(listOf(it)) },
            onDelete = { confirmDelete = listOf(it) },
        )
    } else {
        val selected = items.filter { it.id in selection }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { if (selection.isNotEmpty()) Text("${selection.size} selected") },
                    navigationIcon = {
                        if (selection.isNotEmpty()) IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                        else IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (selection.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarAction(Icons.Rounded.LockOpen, "Move out", { restore(selected) })
                            BarAction(Icons.Rounded.Delete, "Delete", { confirmDelete = selected })
                        }
                    }
                }
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                        DotLargeTitle("LOCKED")
                        Text(
                            "Only on this phone. Hidden from other apps. Uninstalling Dot Gallery deletes these items.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DotEmptyState(
                            "Nothing locked yet",
                            "Select photos in Photos, then tap Lock to move them here.",
                            icon = Icons.Rounded.Lock,
                        )
                    }
                }
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    MediaThumbnail(
                        model = c.locked.file(item),
                        modifier = Modifier.padding(1.dp),
                        contentDescription = item.name,
                        selected = item.id in selection,
                        selectionMode = selection.isNotEmpty(),
                        onClick = {
                            if (selection.isNotEmpty()) selection = if (item.id in selection) selection - item.id else selection + item.id
                            else viewing = index
                        },
                        onLongClick = { selection = selection + item.id },
                    ) {
                        if (item.isVideo) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
                    }
                }
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (list.size == 1) "Delete forever?" else "Delete ${list.size} items forever?") },
            text = { Text("Items in Locked folder skip the bin. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        list.forEach { c.locked.delete(it) }
                        selection = emptySet()
                        viewing = null
                    }
                }) { Text("Delete", color = DotTheme.extra.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LockedGate(canLock: Boolean, onUnlock: () -> Unit, onSetUp: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).dotGrid(DotTheme.extra.dots)) {
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(20.dp))
            DotLargeTitle("LOCKED FOLDER")
            Spacer(Modifier.height(8.dp))
            Text(
                if (canLock) "Unlock with your fingerprint, face or screen lock." else "Set a screen lock (PIN, pattern or password) on this phone to use Locked folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            if (canLock) DotPrimaryButton("Unlock", onClick = onUnlock, accent = true, icon = Icons.Rounded.LockOpen)
            else DotPrimaryButton("Set screen lock", onClick = onSetUp, accent = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LockedViewer(
    items: List<LockedItem>,
    initial: Int,
    onBack: () -> Unit,
    onRestore: (LockedItem) -> Unit,
    onDelete: (LockedItem) -> Unit,
) {
    val c = galleryContainer()
    val pager = rememberPagerState(initialPage = initial) { items.size }
    var chrome by remember { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { items.getOrNull(it)?.id ?: -1L }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            val file = c.locked.file(item)
            if (item.isVideo) {
                VideoPage(Uri.fromFile(file), active = page == pager.settledPage, onTap = { chrome = !chrome })
            } else {
                ZoomableAsyncImage(model = file, contentDescription = item.name, modifier = Modifier.fillMaxSize(), onClick = { chrome = !chrome })
            }
        }
        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
        }
        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val current = items.getOrNull(pager.currentPage)
                BarAction(Icons.Rounded.LockOpen, "Move out", { current?.let(onRestore) }, tint = Color.White)
                BarAction(Icons.Rounded.Delete, "Delete", { current?.let(onDelete) }, tint = Color.White)
            }
        }
    }
}
