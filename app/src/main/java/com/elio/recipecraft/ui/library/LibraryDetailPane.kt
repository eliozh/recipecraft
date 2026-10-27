package com.elio.recipecraft.ui.library

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.recipe.RecipeDetailScreen
import com.elio.recipecraft.ui.recipe.RecipeDetailViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The library list with the [selectedId] recipe's detail beside it, for wide windows. Back, or the
 * detail's own back arrow, closes the detail pane; a predictive back gesture shrinks it as it goes.
 */
@Composable
fun LibraryListDetail(
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenChat: () -> Unit,
    libraryViewModel: LibraryViewModel,
    detailViewModel: @Composable (recipeId: String) -> RecipeDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Registered before the detail's sheets and dialogs, so those still get back first.
    PredictiveBackHandler(enabled = selectedId != null) { events ->
        try {
            events.collect { backProgress.snapTo(it.progress) }
            onSelect(null)
            backProgress.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f) }
            throw e
        }
    }
    Row(modifier.fillMaxSize()) {
        LibraryScreen(
            onOpenChat = onOpenChat,
            onOpenRecipe = onSelect,
            selectedId = selectedId,
            viewModel = libraryViewModel,
            modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
        )
        VerticalDivider()
        Box(
            Modifier
                .weight(1f - LIST_WEIGHT)
                .fillMaxHeight()
                .graphicsLayer {
                    val scale = 1f - BACK_SHRINK * backProgress.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = 1f - backProgress.value / 2
                }
                .testTag(LibraryTestTags.DETAIL_PANE),
        ) {
            if (selectedId == null) {
                Text(
                    stringResource(R.string.library_select_recipe),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                key(selectedId) {
                    RecipeDetailScreen(
                        onBack = { onSelect(null) },
                        onDeleted = { onSelect(null) },
                        onOpenRecipe = onSelect,
                        viewModel = detailViewModel(selectedId),
                    )
                }
            }
        }
    }
}

private const val LIST_WEIGHT = 0.4f
private const val BACK_SHRINK = 0.1f
