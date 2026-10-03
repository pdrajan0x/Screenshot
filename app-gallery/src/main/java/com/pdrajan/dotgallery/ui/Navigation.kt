package com.pdrajan.dotgallery.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dotgallery.GalleryContainer
import kotlinx.coroutines.flow.StateFlow

/** Kinds of media lists reachable from Collections. */
object ListKind {
    const val ALBUM = "album"
    const val FOLDER = "folder"
    const val TAG = "tag"
    const val PERSON = "person"
    const val FAVORITES = "favorites"
    const val VIDEOS = "videos"
    const val ARCHIVE = "archive"
    const val RECENT = "recent"
    const val SCREENSHOTS = "screenshots"
    const val BLURRY = "blurry"
    const val LARGE_VIDEOS = "large-videos"
}

class GalleryNav(private val nav: NavHostController, private val c: GalleryContainer) {
    fun viewer(ids: List<Long>, id: Long) {
        c.viewerIds = ids
        nav.navigate("viewer/$id")
    }
    fun list(kind: String, arg: String = "-") = nav.navigate("list/$kind/${Uri.encode(arg)}")
    fun people() = nav.navigate("people")
    fun bin() = nav.navigate("bin")
    fun locked() = nav.navigate("locked")
    fun utilities() = nav.navigate("utilities")
    fun settings() = nav.navigate("settings")
    fun edit(id: Long) = nav.navigate("edit/$id")
    fun trim(id: Long) = nav.navigate("trim/$id")
    fun back() = nav.popBackStack()
}

@Composable
fun GalleryNavHost(c: GalleryContainer, external: StateFlow<Uri?>, onExternalHandled: () -> Unit) {
    val nav = rememberNavController()
    val g = GalleryNav(nav, c)
    val onboarded by c.settings.onboarded.collectAsStateWithLifecycle()
    val externalUri by external.collectAsStateWithLifecycle()

    LaunchedEffect(externalUri) {
        val uri = externalUri ?: return@LaunchedEffect
        nav.navigate("external/${Uri.encode(uri.toString())}")
        onExternalHandled()
    }

    NavHost(
        navController = nav,
        startDestination = if (onboarded) "main" else "onboarding",
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable("onboarding") {
            OnboardingScreen(onDone = { nav.navigate("main") { popUpTo("onboarding") { inclusive = true } } })
        }
        composable("main") { MainScreen(g) }
        composable("viewer/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            ViewerScreen(initialId = e.arguments?.getLong("id") ?: return@composable, nav = g)
        }
        composable("external/{uri}", arguments = listOf(navArgument("uri") { type = NavType.StringType })) { e ->
            ExternalViewerScreen(uri = Uri.parse(e.arguments?.getString("uri") ?: return@composable), onBack = { g.back() })
        }
        composable(
            "list/{kind}/{arg}",
            arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("arg") { type = NavType.StringType }),
        ) { e ->
            MediaListScreen(kind = e.arguments?.getString("kind") ?: return@composable, arg = e.arguments?.getString("arg") ?: "-", nav = g)
        }
        composable("people") { PeopleScreen(g) }
        composable("bin") { BinScreen(g) }
        composable("locked") { LockedScreen(g) }
        composable("utilities") { UtilitiesScreen(g) }
        composable("settings") { SettingsScreen(g) }
        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            EditorScreen(id = e.arguments?.getLong("id") ?: return@composable, nav = g)
        }
        composable("trim/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            VideoTrimScreen(id = e.arguments?.getLong("id") ?: return@composable, nav = g)
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Rounded.Photo),
    COLLECTIONS("Collections", Icons.Rounded.Collections),
    SEARCH("Search", Icons.Rounded.Search),
}

@Composable
private fun MainScreen(nav: GalleryNav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val bar: @Composable () -> Unit = { DotBottomBar(tab) { tab = it } }
    when (Tab.entries[tab]) {
        Tab.PHOTOS -> PhotosScreen(nav, bottomBar = bar)
        Tab.COLLECTIONS -> CollectionsScreen(nav, bottomBar = bar)
        Tab.SEARCH -> SearchScreen(nav, bottomBar = bar)
    }
}

/** Nothing-style tab bar: icons + mono labels, a red dot under the active tab. */
@Composable
private fun DotBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.forEachIndexed { i, t ->
                    val active = i == selected
                    Column(
                        Modifier.clip(CircleShape).clickable { onSelect(i) }.padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            t.icon,
                            contentDescription = t.label,
                            tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            t.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier.size(4.dp).clip(CircleShape)
                                .background(if (active) DotTheme.extra.accent else androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}
