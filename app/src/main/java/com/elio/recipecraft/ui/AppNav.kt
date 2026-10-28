package com.elio.recipecraft.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.os.bundleOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import com.elio.recipecraft.ui.cover.CookModeScreen
import com.elio.recipecraft.ui.cover.CookModeViewModel
import com.elio.recipecraft.ui.library.DetailPaneStore
import com.elio.recipecraft.ui.library.LibraryListDetail
import com.elio.recipecraft.ui.library.LibraryScreen
import com.elio.recipecraft.ui.library.LibraryViewModel
import com.elio.recipecraft.ui.recipe.RecipeDetailScreen
import com.elio.recipecraft.ui.recipe.RecipeDetailViewModel
import com.elio.recipecraft.ui.settings.SettingsScreen

/** Top-level destinations shown in the bottom navigation bar or the navigation rail. */
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

    AdaptiveScaffold(
        layout = currentAppLayout(),
        isSelected = { destination -> currentDestination?.hierarchy?.any { it.route == destination.route } == true },
        onSelect = navController::navigateToTopLevel,
        // Cook mode takes the whole window.
        showNavigation = currentDestination?.route != COOK_ROUTE,
    ) {
        NavHost(navController = navController, startDestination = TopLevelDestination.CHAT.route) {
            composable(TopLevelDestination.CHAT.route) {
                ChatScreen(onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) })
            }
            libraryGraph(navController, onOpenChat = { navController.navigateToTopLevel(TopLevelDestination.CHAT) })
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

/**
 * The Library tab: the list, the detail screen of a recipe opened from it, and Cook mode for that
 * recipe. A wide window shows the list and detail side by side on the list's route, with the open
 * recipe kept in that entry's saved state; resizing moves an open recipe between the pane and its
 * own screen. Cook mode is its own screen at every size, so folding and unfolding keep it open on
 * the same step. The view models are parameters so tests can supply their own.
 */
internal fun NavGraphBuilder.libraryGraph(
    navController: NavHostController,
    onOpenChat: () -> Unit,
    libraryViewModel: @Composable () -> LibraryViewModel = { hiltViewModel() },
    detailViewModel: @Composable () -> RecipeDetailViewModel = { hiltViewModel() },
    paneDetailViewModel: @Composable (owner: ViewModelStoreOwner, recipeId: String) -> RecipeDetailViewModel =
        { owner, id -> hiltPaneDetailViewModel(owner, id) },
    cookViewModel: @Composable () -> CookModeViewModel = { hiltViewModel() },
) {
    val onCook = { id: String -> navController.navigate(cookRoute(id)) }
    navigation(startDestination = LIBRARY_ROUTE, route = TopLevelDestination.LIBRARY.route) {
        composable(LIBRARY_ROUTE) { entry ->
            val selectedId by entry.savedStateHandle.getStateFlow<String?>(SELECTED_RECIPE_KEY, null)
                .collectAsStateWithLifecycle()
            val listDetail = LocalAppLayout.current.listDetail
            val pane = viewModel<DetailPaneStore>()
            // Closing the pane, or the window getting narrow, lets the last recipe's view model go.
            LaunchedEffect(pane, selectedId, listDetail) { if (!listDetail || selectedId == null) pane.keepOnly(null) }
            if (listDetail) {
                LibraryListDetail(
                    selectedId = selectedId,
                    onSelect = { id -> entry.savedStateHandle[SELECTED_RECIPE_KEY] = id },
                    onOpenChat = onOpenChat,
                    onCook = onCook,
                    libraryViewModel = libraryViewModel(),
                    detailViewModel = { id -> paneDetailViewModel(pane.ownerFor(id), id) },
                )
            } else {
                // The window got narrow with a recipe open beside the list: open it on its own screen.
                LaunchedEffect(selectedId) {
                    val id = selectedId ?: return@LaunchedEffect
                    entry.savedStateHandle[SELECTED_RECIPE_KEY] = null
                    navController.navigate(recipeDetailRoute(id))
                }
                LibraryScreen(
                    onOpenChat = onOpenChat,
                    onOpenRecipe = { id -> navController.navigate(recipeDetailRoute(id)) },
                    viewModel = libraryViewModel(),
                )
            }
        }
        composable(
            RECIPE_DETAIL_ROUTE,
            arguments = listOf(navArgument(RecipeDetailViewModel.ARG_RECIPE_ID) { type = NavType.StringType }),
        ) { entry ->
            if (LocalAppLayout.current.listDetail) {
                // The window got wide: show this recipe beside the list instead.
                LaunchedEffect(Unit) {
                    val id = entry.arguments?.getString(RecipeDetailViewModel.ARG_RECIPE_ID)
                    navController.getBackStackEntry(LIBRARY_ROUTE).savedStateHandle[SELECTED_RECIPE_KEY] = id
                    navController.popBackStack(LIBRARY_ROUTE, inclusive = false)
                }
            }
            RecipeDetailScreen(
                onBack = { navController.popBackStack() },
                // The library under it is still on the back stack, with its filter and search.
                onDeleted = { navController.popBackStack(LIBRARY_ROUTE, inclusive = false) },
                onOpenRecipe = { id -> navController.navigate(recipeDetailRoute(id)) },
                onCook = onCook,
                viewModel = detailViewModel(),
            )
        }
        composable(
            COOK_ROUTE,
            arguments = listOf(navArgument(CookModeViewModel.ARG_RECIPE_ID) { type = NavType.StringType }),
        ) {
            CookModeScreen(onClose = { navController.popBackStack() }, viewModel = cookViewModel())
        }
    }
}

/**
 * The pane's detail view model for [recipeId], kept in [owner], the open recipe's store in
 * [DetailPaneStore]. The recipe id goes in as the default arguments, where the detail route would
 * put it, and the view model's saved state lives and goes with [owner].
 */
@Composable
private fun hiltPaneDetailViewModel(owner: ViewModelStoreOwner, recipeId: String): RecipeDetailViewModel {
    val entry = checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    val defaults = entry as HasDefaultViewModelProviderFactory
    return viewModel(
        viewModelStoreOwner = owner,
        key = "recipe_detail_pane/$recipeId",
        factory = rememberHiltViewModelFactory(defaults.defaultViewModelProviderFactory),
        extras = MutableCreationExtras(defaults.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, bundleOf(RecipeDetailViewModel.ARG_RECIPE_ID to recipeId))
            set(VIEW_MODEL_STORE_OWNER_KEY, owner)
        },
    )
}

private const val LIBRARY_ROUTE = "library"
private const val SELECTED_RECIPE_KEY = "selected_recipe"
private const val RECIPE_DETAIL_ROUTE = "recipe/{${RecipeDetailViewModel.ARG_RECIPE_ID}}"

private const val COOK_ROUTE = "cook/{${CookModeViewModel.ARG_RECIPE_ID}}"

private fun recipeDetailRoute(id: String) = "recipe/${Uri.encode(id)}"

private fun cookRoute(id: String) = "cook/${Uri.encode(id)}"

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
