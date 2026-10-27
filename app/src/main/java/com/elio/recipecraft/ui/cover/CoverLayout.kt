package com.elio.recipecraft.ui.cover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.TopLevelDestination

// The cover screen of a flip phone is a small near-square window, about 320 dp a side once the
// system bars are out. These pieces keep its chrome to a 48 dp top bar and a 56 dp icon-only
// navigation bar, so the content keeps most of the height, and every target stays at least 48 dp.

/** The cover-screen size of a Galaxy Z Flip: 948 × 1048 px at 480 dpi, about 316 × 349 dp. */
@Preview(name = "Cover screen", widthDp = 316, heightDp = 349, showSystemUi = false)
annotation class CoverPreview

/** The minimum height and width of anything to tap on the cover screen. */
val CoverTouchTarget = 48.dp

/**
 * A 48 dp top bar: an optional [navigationIcon], the [title] on one line, and [actions]. It takes
 * the status bar inset itself, as the screens' top app bars do.
 */
@Composable
fun CoverTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(CoverTouchTarget)
                .padding(horizontal = 4.dp),
        ) {
            navigationIcon()
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            actions()
        }
    }
}

/**
 * The top-level destinations as icons only, for the cover screen: a full Material navigation bar
 * with labels is 80 dp tall, a quarter of the window. Each label is the icon's content description.
 */
@Composable
fun CoverNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(COVER_NAVIGATION_HEIGHT)
                .selectableGroup()
                .testTag(CoverTestTags.NAVIGATION),
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = isSelected(destination)
                val label = stringResource(destination.labelRes)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = CoverTouchTarget)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                        .semantics { contentDescription = label },
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Box(Modifier.width(64.dp).height(32.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(destination.iconRes), contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

/** A section heading that shows or hides its content; folded sections keep the cover screen short. */
@Composable
fun CoverSectionHeader(
    title: String,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stringResource(if (expanded) R.string.cover_section_expanded else R.string.cover_section_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CoverTouchTarget)
            .toggleable(value = expanded, role = Role.Button, onValueChange = onExpandedChanged)
            .semantics { stateDescription = state },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

private val COVER_NAVIGATION_HEIGHT = 56.dp

/** Stable handles for UI tests. */
object CoverTestTags {
    const val NAVIGATION = "cover_navigation"
}
