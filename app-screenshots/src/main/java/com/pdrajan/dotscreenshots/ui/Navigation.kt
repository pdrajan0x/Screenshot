package com.pdrajan.dotscreenshots.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.scaleOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdrajan.dot.design.Emphasized
import com.pdrajan.dot.design.EmphasizedAccelerate
import com.pdrajan.dot.design.EmphasizedDecelerate
import com.pdrajan.dot.design.LocalNavAnimatedScope
import com.pdrajan.dot.design.LocalSharedTransitionScope
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.DotScreenshotsApp

/** Where the viewer's swipe list comes from. */
object ShotContext {
    const val ALL = "all"
    const val SEARCH = "search"
    const val FAVORITES = "fav"
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

/** The app's screens, with pictures flying between them (see [sharedImage]). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DotScreenshotsNavHost(container: AppContainer) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) { NavGraph(container) }
    }
}

private fun NavBackStackEntry.isViewer() = destination.route?.startsWith("detail") == true

/** Opened over the screen it came from, without sliding: the viewer and search. */
private fun NavBackStackEntry.fadesIn() = isViewer() || destination.route?.startsWith("search") == true

/** Back to the screen underneath: it fades back in (search, the viewer) or slides back. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (initialState.fadesIn()) fadeIn(tween(220, easing = LinearOutSlowInEasing))
    else slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { -it / 8 } + fadeIn(tween(250, 50, LinearOutSlowInEasing))

/** Closing: the viewer fades away while settling back a little, other screens slide off. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition = when {
    initialState.isViewer() -> fadeOut(tween(240, easing = FastOutLinearInEasing)) + scaleOut(tween(280, easing = Emphasized), targetScale = 0.94f)
    initialState.fadesIn() -> fadeOut(tween(220, easing = FastOutLinearInEasing))
    else -> slideOutHorizontally(tween(350, easing = EmphasizedAccelerate)) { it / 8 } + fadeOut(tween(200, easing = FastOutLinearInEasing))
}

/** A destination whose content can join shared transitions. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
}

@Composable
private fun NavGraph(container: AppContainer) {
    val nav = rememberNavController()
    val onboarded by container.settings.onboardingDone.collectAsStateWithLifecycle()

    fun openDetail(id: Long, ctx: String) = nav.navigate("detail/$id?ctx=${Uri.encode(ctx)}")

    NavHost(
        navController = nav,
        startDestination = if (onboarded) "home" else "onboarding",
        // The viewer and search fade in over the screen they came from (the picture itself flies
        // out of its thumbnail); other screens slide in a little from the side, like Android's own.
        enterTransition = {
            if (targetState.fadesIn()) fadeIn(tween(300, easing = LinearOutSlowInEasing))
            else slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { it / 8 } + fadeIn(tween(250, 50, LinearOutSlowInEasing))
        },
        exitTransition = {
            if (targetState.fadesIn()) fadeOut(tween(200, 150, FastOutLinearInEasing))
            else slideOutHorizontally(tween(400, easing = EmphasizedDecelerate)) { -it / 8 } + fadeOut(tween(200, easing = FastOutLinearInEasing))
        },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
        // The back gesture plays the same closing animation, following the finger (the library's
        // own shrinks the screen into a small card).
        predictivePopEnterTransition = { popEnter() },
        predictivePopExitTransition = { popExit() },
    ) {
        screen("onboarding") {
            OnboardingScreen(onDone = {
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        screen("home") {
            HomeScreen(
                onOpenShot = { id -> openDetail(id, ShotContext.ALL) },
                onSearch = { nav.navigate("search") },
                onOpenCollection = { nav.navigate("list/${Uri.encode(ShotContext.collection(it))}") },
                onOpenCategory = { nav.navigate("list/${Uri.encode(ShotContext.category(it))}") },
                onOpenFavorites = { nav.navigate("list/${ShotContext.FAVORITES}") },
                onSettings = { nav.navigate("settings") },
            )
        }
        screen(
            "search?q={q}",
            arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenShot = { id -> openDetail(id, ShotContext.SEARCH) },
                initialQuery = entry.arguments?.getString("q").orEmpty(),
            )
        }
        screen(
            "detail/{id}?ctx={ctx}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("ctx") { type = NavType.StringType; defaultValue = ShotContext.ALL },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@screen
            val ctx = entry.arguments?.getString("ctx") ?: ShotContext.ALL
            DetailScreen(
                initialId = id,
                context = ctx,
                onBack = { nav.popBackStack() },
                onOpenCollection = { nav.navigate("list/${Uri.encode(ShotContext.collection(it))}") },
                onSearch = { q -> nav.navigate("search?q=${Uri.encode(q)}") },
                onEdit = { nav.navigate("edit/$it") },
            )
        }
        screen("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            EditScreen(id = entry.arguments?.getLong("id") ?: return@screen, onBack = { nav.popBackStack() })
        }
        screen(
            "list/{ctx}",
            arguments = listOf(navArgument("ctx") { type = NavType.StringType }),
        ) { entry ->
            val ctx = entry.arguments?.getString("ctx") ?: return@screen
            ShotListScreen(
                context = ctx,
                onBack = { nav.popBackStack() },
                onOpenShot = { id -> openDetail(id, ctx) },
            )
        }
        screen("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
