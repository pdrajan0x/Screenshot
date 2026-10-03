package com.pdrajan.dotscreenshots.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.DotScreenshotsApp

/** Where the viewer's swipe list comes from. */
object ShotContext {
    const val ALL = "all"
    const val SEARCH = "search"
    const val FAVORITES = "fav"
    const val SINGLE = "single"
    fun category(id: String) = "cat:$id"
    fun collection(id: Long) = "col:$id"
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DotScreenshotsApp).container

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun DotScreenshotsNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val onboarded by container.settings.onboardingDone.collectAsStateWithLifecycle()

    fun openDetail(id: Long, ctx: String) = nav.navigate("detail/$id?ctx=${Uri.encode(ctx)}")

    NavHost(
        navController = nav,
        startDestination = if (onboarded) "home" else "onboarding",
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable("onboarding") {
            OnboardingScreen(onDone = {
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                onOpenShot = { id -> openDetail(id, ShotContext.ALL) },
                onSearch = { nav.navigate("search") },
                onOpenCollection = { nav.navigate("list/${Uri.encode(ShotContext.collection(it))}") },
                onOpenCategory = { nav.navigate("list/${Uri.encode(ShotContext.category(it))}") },
                onOpenFavorites = { nav.navigate("list/${ShotContext.FAVORITES}") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("search") {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenShot = { id -> openDetail(id, ShotContext.SEARCH) },
            )
        }
        composable(
            "detail/{id}?ctx={ctx}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("ctx") { type = NavType.StringType; defaultValue = ShotContext.ALL },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val ctx = entry.arguments?.getString("ctx") ?: ShotContext.ALL
            DetailScreen(
                initialId = id,
                context = ctx,
                onBack = { nav.popBackStack() },
                onOpenShot = { openDetail(it, ShotContext.SINGLE) },
                onOpenCollection = { nav.navigate("list/${Uri.encode(ShotContext.collection(it))}") },
            )
        }
        composable(
            "list/{ctx}",
            arguments = listOf(navArgument("ctx") { type = NavType.StringType }),
        ) { entry ->
            val ctx = entry.arguments?.getString("ctx") ?: return@composable
            ShotListScreen(
                context = ctx,
                onBack = { nav.popBackStack() },
                onOpenShot = { id -> openDetail(id, ctx) },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
