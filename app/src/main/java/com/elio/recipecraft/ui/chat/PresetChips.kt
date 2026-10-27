package com.elio.recipecraft.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope

/** What tapping a preset chip does, with its text already in the app language. */
sealed interface PresetAction {
    /** Sends [request] as if from the composer. */
    data class Generate(val request: GenerationRequest) : PresetAction

    /** Sends [text] as a free-form chat turn. */
    data class Send(val text: String) : PresetAction

    /** Puts [text] in the input for the user to finish before sending. */
    data class Fill(val text: String) : PresetAction
}

/** The template behind a chip; the string resources are resolved in the app language when it is tapped. */
sealed interface PresetTemplate {
    data class Generate(val scope: GenerationScope, val count: Int, @param:StringRes val preferences: Int? = null) : PresetTemplate

    data class Send(@param:StringRes val text: Int) : PresetTemplate

    data class Fill(@param:StringRes val text: Int) : PresetTemplate
}

/**
 * The preset prompts, each a localized [label] and the [template] it sends or fills. Starter
 * chips ([forEmptyChat]) show on an empty chat; the rest refer to the recipes above, so they
 * only show once the chat has messages.
 */
enum class PresetChip(@param:StringRes val label: Int, val template: PresetTemplate, val forEmptyChat: Boolean) {
    FULL_DAY_PLAN(R.string.preset_full_day, PresetTemplate.Generate(GenerationScope.FULL_DAY_SET, 1), forEmptyChat = true),
    QUICK_BREAKFASTS(
        R.string.preset_quick_breakfasts,
        PresetTemplate.Generate(GenerationScope.BREAKFAST, 3, R.string.preset_quick_breakfasts_preferences),
        forEmptyChat = true,
    ),
    VEGETARIAN_DAY(
        R.string.preset_vegetarian_day,
        PresetTemplate.Generate(GenerationScope.FULL_DAY_SET, 1, R.string.preset_vegetarian_day_preferences),
        forEmptyChat = true,
    ),
    FRIDGE(R.string.preset_fridge, PresetTemplate.Fill(R.string.preset_fridge_text), forEmptyChat = true),
    MAKE_IT_VEGETARIAN(R.string.preset_make_vegetarian, PresetTemplate.Send(R.string.preset_make_vegetarian_text), forEmptyChat = false),
    UNDER_500_KCAL(R.string.preset_under_500, PresetTemplate.Send(R.string.preset_under_500_text), forEmptyChat = false),
    ;

    /** What tapping the chip does, with [string] resolving its texts in the app language. */
    fun action(string: (Int) -> String): PresetAction = when (val t = template) {
        is PresetTemplate.Generate ->
            PresetAction.Generate(GenerationRequest(t.scope, t.count, t.preferences?.let(string).orEmpty()))
        is PresetTemplate.Send -> PresetAction.Send(string(t.text))
        is PresetTemplate.Fill -> PresetAction.Fill(string(t.text))
    }

    companion object {
        /** The chips for a chat with or without messages; follow-ups come first once there are messages. */
        fun forChat(empty: Boolean): List<PresetChip> =
            if (empty) entries.filter { it.forEmptyChat } else entries.sortedBy { it.forEmptyChat }
    }
}

/**
 * The preset chips: wrapped onto several lines when [wrap], otherwise one row that scrolls
 * sideways. They are disabled while a reply is in progress.
 */
@Composable
fun PresetChips(
    chips: List<PresetChip>,
    enabled: Boolean,
    onAction: (PresetAction) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
) {
    val resources = LocalResources.current
    val chip: @Composable (PresetChip) -> Unit = { preset ->
        SuggestionChip(
            onClick = { onAction(preset.action(resources::getString)) },
            label = { Text(stringResource(preset.label)) },
            enabled = enabled,
            modifier = Modifier.testTag(PresetChipsTestTags.chip(preset)),
        )
    }
    if (wrap) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = modifier.testTag(PresetChipsTestTags.CHIPS),
        ) {
            chips.forEach { chip(it) }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag(PresetChipsTestTags.CHIPS),
        ) {
            chips.forEach { chip(it) }
        }
    }
}

/** Stable handles for UI tests. */
object PresetChipsTestTags {
    const val CHIPS = "preset_chips"

    fun chip(chip: PresetChip) = "preset_${chip.name.lowercase()}"
}
