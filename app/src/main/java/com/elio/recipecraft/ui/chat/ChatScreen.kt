package com.elio.recipecraft.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.ui.common.isFixedInSettings
import com.elio.recipecraft.ui.common.llmErrorText
import com.elio.recipecraft.domain.GenerationShortfall
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.daySets
import com.elio.recipecraft.ui.recipe.RecipeCard
import com.elio.recipecraft.ui.recipe.label

@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.shownText?.length, last?.recipeStatus, last?.recipes?.size, state.error) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && state.error == null) {
                Text(
                    text = stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().testTag(ChatTestTags.MESSAGES),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatItem(
                        message = message,
                        streaming = message.id == state.streamingMessageId,
                        canRetry = !state.sending,
                        onRetry = { viewModel.onRetry(message.id) },
                        cards = CardActions(
                            state = state,
                            onSave = viewModel::onSave,
                            onSaveAll = viewModel::onSaveAll,
                            onModify = { recipe -> viewModel.onModify(message.id, recipe.id) },
                            onUndo = { recipe -> viewModel.onUndoModify(message.id, recipe.id) },
                        ),
                    )
                }
                if (state.saveFailed) {
                    item(key = "save_failed") { SaveFailedCard(onDismiss = viewModel::onSaveFailedDismissed) }
                }
                state.error?.let { error ->
                    item(key = "error") { ErrorCard(error, onOpenSettings) }
                }
            }
        }
        if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        GenerationComposer(
            state = state.composer,
            sending = state.sending,
            onScopeChanged = viewModel::onScopeChanged,
            onCountChanged = viewModel::onCountChanged,
            onPreferencesChanged = viewModel::onPreferencesChanged,
            onExpandedChanged = viewModel::onComposerExpandedChanged,
            onGenerate = viewModel::onGenerate,
        )
        state.modify?.let { modify ->
            ModifySheet(
                recipeName = state.messages.firstOrNull { it.id == modify.messageId }
                    ?.recipes?.firstOrNull { it.id == modify.recipeId }?.name.orEmpty(),
                instruction = modify.instruction,
                running = modify.running,
                error = modify.error,
                onInstructionChanged = viewModel::onModifyInstructionChanged,
                onSend = viewModel::onModifySend,
                onCancel = viewModel::onModifyCancel,
            )
        }
        Composer(
            input = state.input,
            sending = state.sending,
            canSend = state.canSend,
            onInputChanged = viewModel::onInputChanged,
            onSend = viewModel::onSend,
            onStop = viewModel::onStop,
        )
    }
}

@Composable
private fun ChatItem(
    message: ChatMessage,
    streaming: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    cards: CardActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Above the raw text, so Retry stays in view when the list scrolls to this reply.
        if (message.recipeStatus == RecipeStatus.FAILED) RecipeFailed(canRetry, onRetry)
        val generation = message.generation
        if (generation != null) {
            MessageBubble(message, generationSummary(generation), streaming)
        } else if (message.shownText.isNotEmpty() || (streaming && message.recipeStatus == RecipeStatus.NONE)) {
            // A recipe reply whose prose is empty shows only its cards.
            MessageBubble(message, message.shownText, streaming)
        }
        when (message.recipeStatus) {
            RecipeStatus.BUILDING -> Progress(stringResource(R.string.chat_building_recipes))
            RecipeStatus.REPAIRING -> Progress(stringResource(R.string.chat_repairing_recipes))
            RecipeStatus.FAILED, RecipeStatus.NONE -> Unit
        }
        message.shortfall?.let { ShortfallWarning(it) }
        Recipes(message.recipes, cards)
    }
}

/** What the recipe cards of a reply show and do. */
private class CardActions(
    val state: ChatUiState,
    val onSave: (Recipe) -> Unit,
    val onSaveAll: (List<Recipe>) -> Unit,
    val onModify: (Recipe) -> Unit,
    val onUndo: (Recipe) -> Unit,
)

/** Single recipes as a plain list, then each full-day set under its "Day n" header with Save all. */
@Composable
private fun Recipes(recipes: List<Recipe>, cards: CardActions) {
    val state = cards.state

    @Composable
    fun ActionCard(recipe: Recipe) {
        val modified = state.isModified(recipe.id)
        RecipeCard(
            recipe,
            saveState = state.saveState(recipe),
            onSave = { cards.onSave(recipe) },
            modified = modified,
            busy = state.isModifying(recipe.id),
            onModify = { cards.onModify(recipe) },
            onUndo = if (modified) ({ cards.onUndo(recipe) }) else null,
        )
    }

    recipes.filter { it.setId == null }.forEach { ActionCard(it) }
    recipes.daySets().forEachIndexed { i, set ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                stringResource(R.string.generation_day, i + 1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
                    .testTag(ChatTestTags.DAY_HEADER),
            )
            val allSaved = set.all { it.id in state.savedIds }
            TextButton(
                onClick = { cards.onSaveAll(set) },
                enabled = !allSaved && set.none { state.isModifying(it.id) },
                modifier = Modifier.testTag(ChatTestTags.SAVE_ALL),
            ) {
                Text(stringResource(if (allSaved) R.string.recipe_all_saved else R.string.recipe_save_all))
            }
        }
        set.forEach { ActionCard(it) }
    }
}

@Composable
private fun SaveFailedCard(onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag(ChatTestTags.SAVE_FAILED),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(stringResource(R.string.chat_save_failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

@Composable
private fun ShortfallWarning(shortfall: GenerationShortfall) {
    val separator = stringResource(R.string.list_separator)
    val lines = buildList {
        if (shortfall.isShort) {
            val plural = if (shortfall.sets) R.plurals.generation_short_sets else R.plurals.generation_short_recipes
            add(pluralStringResource(plural, shortfall.asked, shortfall.asked, shortfall.got))
        }
        shortfall.incomplete.forEach { set ->
            val meals = set.missing.map { stringResource(it.label) }.joinToString(separator)
            add(stringResource(R.string.generation_incomplete_set, set.day, meals))
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag(ChatTestTags.GENERATION_WARNING),
    ) {
        Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Progress(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp).testTag(ChatTestTags.RECIPE_PROGRESS),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecipeFailed(canRetry: Boolean, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag(ChatTestTags.RECIPE_FAILED),
    ) {
        Text(
            stringResource(R.string.chat_recipe_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRetry, enabled = canRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, text: String, streaming: Boolean) {
    val isUser = message.role == LlmMessage.Role.USER
    Box(Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 560.dp)
                .testTag(if (isUser) ChatTestTags.USER_MESSAGE else ChatTestTags.ASSISTANT_MESSAGE),
        ) {
            Text(
                text = text.ifEmpty { if (streaming) "…" else "" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ErrorCard(error: LlmErrorKind, onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag(ChatTestTags.ERROR),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(llmErrorText(error), style = MaterialTheme.typography.bodyMedium)
            if (error.isFixedInSettings) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                    Text(stringResource(R.string.chat_open_settings))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    sending: Boolean,
    canSend: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f).testTag(ChatTestTags.INPUT),
        )
        if (sending) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.chat_stop)) }
        } else {
            Button(onClick = onSend, enabled = canSend) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

/** Stable handles for UI tests. */
object ChatTestTags {
    const val MESSAGES = "chat_messages"
    const val USER_MESSAGE = "chat_user_message"
    const val ASSISTANT_MESSAGE = "chat_assistant_message"
    const val ERROR = "chat_error"
    const val RECIPE_PROGRESS = "chat_recipe_progress"
    const val RECIPE_FAILED = "chat_recipe_failed"
    const val INPUT = "chat_input"
    const val DAY_HEADER = "chat_day_header"
    const val GENERATION_WARNING = "chat_generation_warning"
    const val SAVE_ALL = "chat_save_all"
    const val SAVE_FAILED = "chat_save_failed"
}
