package com.elio.recipecraft.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.window.core.layout.WindowSizeClass
import com.elio.recipecraft.ui.cover.CoverNavigationBar

/** Where the top-level destinations live. */
enum class NavigationType { BOTTOM_BAR, RAIL }

/** How the app lays itself out in the current window. */
data class AppLayout(
    val navigation: NavigationType,
    /** The library list and a recipe's detail side by side. */
    val listDetail: Boolean,
    /** A short window, such as a phone in landscape: screens fold away what they can. */
    val compactHeight: Boolean,
    /**
     * A small near-square window, such as the cover screen of a flip phone: screens keep only the
     * essentials, with large targets and no horizontal scrolling, and the navigation bar shows icons only.
     */
    val cover: Boolean = false,
) {
    companion object {
        /** A phone in portrait; also what screens see outside [AdaptiveScaffold], as in tests. */
        val Compact = AppLayout(NavigationType.BOTTOM_BAR, listDetail = false, compactHeight = false)
    }
}

/**
 * Compact width gets a bottom bar; medium width a navigation rail; expanded width a rail and the
 * library list and detail side by side. Compact width and compact height together (under 600 dp
 * wide and 480 dp tall) is the cover layout: the Galaxy Z Flip cover screen, about 316 × 349 dp,
 * or a phone window that small in split screen.
 */
fun appLayoutFor(sizeClass: WindowSizeClass): AppLayout {
    val medium = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val expanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val compactHeight = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    return AppLayout(
        navigation = if (medium) NavigationType.RAIL else NavigationType.BOTTOM_BAR,
        listDetail = expanded,
        compactHeight = compactHeight,
        cover = !medium && compactHeight,
    )
}

/** The layout of the window the screen is in. */
val LocalAppLayout = compositionLocalOf { AppLayout.Compact }

/** The [AppLayout] of the current window; it changes as the window is resized, folded, or rotated. */
@Composable
fun currentAppLayout(): AppLayout = appLayoutFor(currentWindowAdaptiveInfo().windowSizeClass)

/**
 * The top-level navigation for [layout], around [content]. The content gets its start, end and
 * bottom insets handled (the system bars, the display cutout in landscape); each screen's top app
 * bar handles the status bar, so its color reaches the top edge. [showNavigation] false hides the
 * bar or rail, for a full-screen task such as Cook mode; the content stays where it is in the
 * composition, so it keeps its state.
 */
@Composable
fun AdaptiveScaffold(
    layout: AppLayout,
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    showNavigation: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppLayout provides layout) {
        when (layout.navigation) {
            NavigationType.BOTTOM_BAR -> Scaffold(
                bottomBar = {
                    when {
                        !showNavigation -> Unit
                        layout.cover -> CoverNavigationBar(isSelected, onSelect)
                        else -> NavigationBar {
                            TopLevelDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = isSelected(destination),
                                    onClick = { onSelect(destination) },
                                    icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                },
                contentWindowInsets = SideInsets,
            ) { innerPadding ->
                // Consumed so screens that add imePadding don't count the bottom bar twice.
                Box(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) { content() }
            }
            NavigationType.RAIL -> Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    if (showNavigation) {
                        NavigationRail(windowInsets = SafeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                            TopLevelDestination.entries.forEach { destination ->
                                NavigationRailItem(
                                    selected = isSelected(destination),
                                    onClick = { onSelect(destination) },
                                    icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (showNavigation) {
                                    // The rail already keeps clear of the start edge.
                                    Modifier
                                        .consumeWindowInsets(SafeInsets.only(WindowInsetsSides.Start))
                                        .windowInsetsPadding(SideInsets.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                                } else {
                                    Modifier.windowInsetsPadding(SideInsets)
                                },
                            ),
                    ) { content() }
                }
            }
        }
    }
}

/** The system bars and the display cutout, which in landscape sits at a side. */
private val SafeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Everything but the top: the screens' app bars take the status bar. The IME is left to the screens. */
private val SideInsets: WindowInsets
    @Composable get() = SafeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
