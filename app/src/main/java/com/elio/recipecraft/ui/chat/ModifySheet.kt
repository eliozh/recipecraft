package com.elio.recipecraft.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.ui.common.modifyErrorText
import com.elio.recipecraft.ui.common.retryingText

/** A quick instruction: [label] on the chip, [text] what it adds to the instruction. */
private enum class ModifyChip(@StringRes val label: Int, @StringRes val text: Int) {
    VEGETARIAN(R.string.modify_chip_vegetarian, R.string.modify_chip_vegetarian_text),
    FEWER_CALORIES(R.string.modify_chip_fewer_calories, R.string.modify_chip_fewer_calories_text),
    NO_OVEN(R.string.modify_chip_no_oven, R.string.modify_chip_no_oven_text),
    SWAP(R.string.modify_chip_swap, R.string.modify_chip_swap_text),
}

/**
 * The Modify with AI sheet for [recipeName]. It holds no state, so any screen can show it for
 * whatever recipe it changes: the instruction, [running] and [error] come from that screen's
 * view model. Quick chips add their text to the instruction. While [running], the instruction
 * is locked and Cancel stops the change; dismissing the sheet cancels too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifySheet(
    recipeName: String,
    instruction: String,
    running: Boolean,
    error: ModifyError?,
    onInstructionChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    /** Set while a rate-limited change waits this many seconds to be sent again. */
    retryingInSeconds: Int? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(ModifySheetTestTags.SHEET),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.recipe_modify),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(recipeName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val separator = stringResource(R.string.modify_instruction_separator)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModifyChip.entries.forEach { chip ->
                    val text = stringResource(chip.text)
                    SuggestionChip(
                        onClick = { onInstructionChanged(appendInstruction(instruction, text, separator)) },
                        label = { Text(stringResource(chip.label)) },
                        enabled = !running,
                    )
                }
            }
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChanged,
                placeholder = { Text(stringResource(R.string.modify_instruction_hint)) },
                enabled = !running,
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag(ModifySheetTestTags.INSTRUCTION),
            )
            if (error != null) {
                Text(
                    modifyErrorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ModifySheetTestTags.ERROR),
                )
            }
            if (running && retryingInSeconds != null) {
                Text(
                    retryingText(retryingInSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ModifySheetTestTags.RETRYING),
                )
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(ModifySheetTestTags.PROGRESS))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag(ModifySheetTestTags.CANCEL)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = onSend,
                    enabled = !running && instruction.isNotBlank(),
                    modifier = Modifier.testTag(ModifySheetTestTags.SEND),
                ) {
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }
}

/** [instruction] with [addition] after it, joined by [separator]; just [addition] when nothing is typed yet. */
internal fun appendInstruction(instruction: String, addition: String, separator: String): String {
    val typed = instruction.trimEnd()
    return if (typed.isEmpty()) addition else typed.trimEnd(*separator.toCharArray()) + separator + addition
}

/** Stable handles for UI tests. */
object ModifySheetTestTags {
    const val SHEET = "modify_sheet"
    const val INSTRUCTION = "modify_instruction"
    const val ERROR = "modify_error"
    const val PROGRESS = "modify_progress"
    const val RETRYING = "modify_retrying"
    const val CANCEL = "modify_cancel"
    const val SEND = "modify_send"
}
