package com.elio.recipecraft.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.chat.ChatScreen
import com.elio.recipecraft.ui.library.LibraryScreen
import com.elio.recipecraft.ui.library.LibraryViewModel
import com.elio.recipecraft.ui.recipe.RecipeDetailScreen
import com.elio.recipecraft.ui.recipe.RecipeDetailViewModel
import com.elio.recipecraft.ui.settings.SettingsScreen

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    CHAT("chat", R.string.tab_chat, R.drawable.ic_tab_chat),
    /** A graph: the library list and the recipe detail screens opened from it. */
    LIBRARY("library_graph", R.string.tab_library, R.drawable.ic_tab_library),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_tab_settings),
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.CHAT.route,
            // Consumed so screens that add imePadding don't count the bottom bar twice.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(TopLevelDestination.CHAT.route) {
                ChatScreen(onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) })
            }
            libraryGraph(navController, onOpenChat = { navController.navigateToTopLevel(TopLevelDestination.CHAT) })
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

/**
 * The Library tab: the list, and the detail screen of a recipe opened from it. The view models are
 * parameters so tests can supply their own.
 */
internal fun NavGraphBuilder.libraryGraph(
    navController: NavHostController,
    onOpenChat: () -> Unit,
    libraryViewModel: @Composable () -> LibraryViewModel = { hiltViewModel() },
    detailViewModel: @Composable () -> RecipeDetailViewModel = { hiltViewModel() },
) {
    navigation(startDestination = LIBRARY_ROUTE, route = TopLevelDestination.LIBRARY.route) {
        composable(LIBRARY_ROUTE) {
            LibraryScreen(
                onOpenChat = onOpenChat,
                onOpenRecipe = { id -> navController.navigate(recipeDetailRoute(id)) },
                viewModel = libraryViewModel(),
            )
        }
        composable(
            RECIPE_DETAIL_ROUTE,
            arguments = listOf(navArgument(RecipeDetailViewModel.ARG_RECIPE_ID) { type = NavType.StringType }),
        ) {
            RecipeDetailScreen(
                onBack = { navController.popBackStack() },
                // The library under it is still on the back stack, with its filter and search.
                onDeleted = { navController.popBackStack(LIBRARY_ROUTE, inclusive = false) },
                onOpenRecipe = { id -> navController.navigate(recipeDetailRoute(id)) },
                viewModel = detailViewModel(),
            )
        }
    }
}

private const val LIBRARY_ROUTE = "library"
private const val RECIPE_DETAIL_ROUTE = "recipe/{${RecipeDetailViewModel.ARG_RECIPE_ID}}"

private fun recipeDetailRoute(id: String) = "recipe/${Uri.encode(id)}"

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
