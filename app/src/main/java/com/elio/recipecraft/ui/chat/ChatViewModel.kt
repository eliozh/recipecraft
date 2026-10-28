package com.elio.recipecraft.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.chat.ChatMessage
import com.elio.recipecraft.data.chat.ChatRepository
import com.elio.recipecraft.data.chat.Conversation
import com.elio.recipecraft.data.chat.RecipeStatus
import com.elio.recipecraft.data.chat.ReplyState
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.ModifyResult
import com.elio.recipecraft.data.llm.Prompt
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.SetGrouping
import com.elio.recipecraft.data.llm.collectReply
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.recipe.SaveState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The generation composer above the chat input. */
data class ComposerState(
    val scope: GenerationScope = GenerationScope.FULL_DAY_SET,
    val count: Int = 1,
    val preferences: String = "",
    val expanded: Boolean = true,
) {
    fun toRequest() = GenerationRequest(scope, count, preferences.trim())
}

/** The Modify with AI sheet, open on the recipe [recipeId] of the reply [messageId]. */
data class ModifyState(
    val messageId: Long,
    val recipeId: String,
    val instruction: String = "",
    /** True while the model works on the change; the card shows progress and Cancel stops it. */
    val running: Boolean = false,
    /** Why the last attempt failed; the card was left as it was. */
    val error: ModifyError? = null,
    /** Set while a rate-limited change waits this many seconds to be sent again. */
    val retryingInSeconds: Int? = null,
) {
    val canSend: Boolean get() = !running && instruction.isNotBlank()
}

/** A chat deleted from the recent list; [wasOpen] when it was the chat on screen, so Undo opens it again. */
data class PendingChatDelete(val conversation: Conversation, val wasOpen: Boolean)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    /** True from Send until the reply finishes, fails or is stopped. */
    val sending: Boolean = false,
    /** The id of the assistant message that is receiving deltas. */
    val streamingMessageId: Long? = null,
    val error: LlmErrorKind? = null,
    /** Set while a rate-limited request waits this many seconds to be sent again. */
    val retryingInSeconds: Int? = null,
    val composer: ComposerState = ComposerState(),
    /** Ids of the recipes already in the library, so their cards show Saved. */
    val savedIds: Set<String> = emptySet(),
    /** The last save did not reach the database. */
    val saveFailed: Boolean = false,
    /** The open Modify with AI sheet, or null. */
    val modify: ModifyState? = null,
    /** The earlier versions of each changed recipe, by id, the latest last, so Undo can go back one at a time. */
    val history: Map<String, List<Recipe>> = emptyMap(),
    /** Each recipe as this chat last saved it, by id, so a card changed since shows Save changes. */
    val savedVersions: Map<String, Recipe> = emptyMap(),
    /** The open conversation, or null until its first turn is stored. */
    val conversationId: Long? = null,
    /** Every stored conversation, the latest first, for the recent chats list. */
    val conversations: List<Conversation> = emptyList(),
    val showRecent: Boolean = false,
    /** The chat just deleted, while its snackbar can still undo it; it stays stored until then. */
    val pendingDelete: PendingChatDelete? = null,
) {
    /** The recent chats list, without the one waiting to be deleted. */
    val visibleConversations: List<Conversation>
        get() = pendingDelete?.let { pending -> conversations.filterNot { it.id == pending.conversation.id } } ?: conversations

    val canSend: Boolean get() = !sending && input.isNotBlank()

    fun isModified(recipeId: String): Boolean = history[recipeId].orEmpty().isNotEmpty()

    fun isModifying(recipeId: String): Boolean = modify?.running == true && modify.recipeId == recipeId

    fun saveState(recipe: Recipe): SaveState {
        if (recipe.id !in savedIds) return SaveState.UNSAVED
        val saved = savedVersions[recipe.id]
        return if (saved == null || saved.sameContent(recipe)) SaveState.SAVED else SaveState.CHANGED
    }

    private companion object {
        /** Calories are worked out again on saving, and updatedAt moves with every change, so neither counts. */
        fun Recipe.sameContent(other: Recipe) = copy(calories = other.calories, updatedAt = other.updatedAt) == other
    }
}

/**
 * The chat. Each message is stored as it completes, so the conversation survives the app being
 * stopped; the undo history of changed recipes is kept in memory only, so it starts empty when a
 * conversation is opened again.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val clientFactory: LlmClientFactory,
    private val promptBuilder: PromptBuilder,
    private val parser: RecipeParser,
    private val assembler: RecipeAssembler,
    private val nutrition: NutritionLookups,
    private val recipes: RecipeRepository,
    private val recipeModifier: RecipeModifier,
    private val chats: ChatRepository,
    /** Keeps the half-typed input and the composer through the app process being stopped. */
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** True when the input and composer came back from before the process was stopped. */
    private val restoredDraft = savedState.contains(KEY_INPUT)

    private val _uiState = MutableStateFlow(
        ChatUiState(input = savedState[KEY_INPUT] ?: "", composer = restoredComposer() ?: ComposerState()),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var nextId = 0L
    private var replyJob: Job? = null
    private var modifyJob: Job? = null

    /**
     * The chat being written to storage. A new or reopened chat gets a new session, so writes still
     * queued for the old one go to the old conversation.
     */
    private class Session {
        var conversationId: Long? = null
    }

    private var session = Session()

    /** Storage writes, run one at a time in order, so a message's last version is always written last. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** True while a write of the streaming reply is queued; it writes whatever text has arrived when it runs. */
    private var partialWriteQueued = false

    /** The text of the streaming reply, which reaches [uiState] at most every [STREAM_UPDATE_MILLIS]. */
    private var replyBuffer: ReplyBuffer? = null

    /** Shows the streaming reply's latest text at the end of each interval; null while no text is waiting. */
    private var updateJob: Job? = null

    /** The streaming reply has text that [uiState] does not show yet. */
    private var replyDirty = false

    init {
        viewModelScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    // Storage failed (Room reports it as a runtime exception); the chat goes on in memory.
                }
            }
        }
        viewModelScope.launch {
            _uiState.map { it.input to it.composer }.distinctUntilChanged().collect { (input, composer) -> saveDraft(input, composer) }
        }
        val first = session
        // A restored composer stays as the user left it; otherwise it folds away over a reopened chat.
        enqueue { chats.lastOpened()?.let { load(first, it, keepComposer = restoredDraft) } }
        viewModelScope.launch {
            recipes.savedIds
                // Room reports an unreadable database as a runtime exception; cards then offer Save, which reports the failure.
                .catch { e -> if (e !is RuntimeException || e is CancellationException) throw e }
                .collect { ids -> _uiState.update { it.copy(savedIds = ids) } }
        }
        viewModelScope.launch {
            chats.conversations
                .catch { e -> if (e !is RuntimeException || e is CancellationException) throw e }
                .collect { list -> _uiState.update { it.copy(conversations = list) } }
        }
    }

    fun onShowRecent() {
        _uiState.update { it.copy(showRecent = true) }
    }

    fun onHideRecent() {
        _uiState.update { it.copy(showRecent = false) }
    }

    /** Starts an empty chat; the conversation is stored once its first turn is sent. */
    fun onNewChat() {
        resetChat()
    }

    /** Opens the stored conversation [id] in place of the current chat. */
    fun onOpenConversation(id: Long) {
        if (id == _uiState.value.conversationId) {
            onHideRecent()
            return
        }
        resetChat()
        val opened = session
        enqueue { load(opened, id) }
    }

    /**
     * Deletes the stored conversation [id] from the list, and starts an empty chat when it was the
     * open one. It is deleted from storage once its snackbar goes away without Undo, so Undo puts it
     * back with its messages; deleting another chat first deletes this one for good.
     */
    fun onDeleteConversation(id: Long) {
        val state = _uiState.value
        val conversation = state.conversations.firstOrNull { it.id == id } ?: return
        commitPendingDelete()
        val open = id == state.conversationId
        _uiState.update { it.copy(pendingDelete = PendingChatDelete(conversation, open)) }
        if (open) resetChat(keepRecentOpen = true)
    }

    /** Undo on the snackbar: the chat comes back, and opens again if it was open and nothing new was started. */
    fun onUndoDeleteConversation(pending: PendingChatDelete) {
        if (_uiState.value.pendingDelete != pending) return
        _uiState.update { it.copy(pendingDelete = null) }
        val state = _uiState.value
        if (pending.wasOpen && state.messages.isEmpty() && !state.sending) onOpenConversation(pending.conversation.id)
    }

    /** The snackbar went away without Undo, so the chat is deleted from storage. */
    fun onDeleteConversationShown(pending: PendingChatDelete) {
        if (_uiState.value.pendingDelete == pending) commitPendingDelete()
    }

    private fun commitPendingDelete() {
        val pending = _uiState.value.pendingDelete ?: return
        _uiState.update { it.copy(pendingDelete = null) }
        enqueue { chats.delete(pending.conversation.id) }
    }

    /** Runs the preset chip's [action]: sends its request or text at once, or puts its text in the input. */
    fun onPreset(action: PresetAction) {
        when (action) {
            is PresetAction.Generate -> generate(action.request)
            is PresetAction.Send -> if (!_uiState.value.sending) sendChat(action.text, clearInput = false)
            is PresetAction.Fill -> onInputChanged(action.text)
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return
        sendChat(state.input.trim(), clearInput = true)
    }

    private fun sendChat(text: String, clearInput: Boolean) {
        startReply { client ->
            val prompt = promptBuilder.chat(_uiState.value.messages.toHistory(), text)
            val replyId = addTurn(ChatMessage(nextId++, LlmMessage.Role.USER, text)) {
                if (clearInput) it.copy(input = "") else it
            }
            reply(client, prompt, replyId, request = null)
        }
    }

    fun onScopeChanged(scope: GenerationScope) = updateComposer { it.copy(scope = scope) }

    fun onCountChanged(count: Int) =
        updateComposer { it.copy(count = count.coerceIn(GenerationRequest.COUNT_RANGE)) }

    fun onPreferencesChanged(text: String) = updateComposer { it.copy(preferences = text) }

    fun onComposerExpandedChanged(expanded: Boolean) = updateComposer { it.copy(expanded = expanded) }

    /** Sends the composer's request; the composer folds away so the reply has room. */
    fun onGenerate() = generate(_uiState.value.composer.toRequest())

    private fun generate(request: GenerationRequest) {
        if (_uiState.value.sending) return
        startReply { client ->
            val prompt = promptBuilder.generate(_uiState.value.messages.toHistory(), request)
            // The filled template is what the model saw, so it is what later turns send as history.
            val user = ChatMessage(
                nextId++,
                LlmMessage.Role.USER,
                prompt.request.messages.last().content,
                shownText = "",
                generation = request,
            )
            val replyId = addTurn(user) { it.copy(composer = it.composer.copy(expanded = false)) }
            reply(client, prompt, replyId, request)
        }
    }

    /**
     * Asks again for the reply [messageId], replacing it where it is; the user turn before it is
     * resent with only the turns before that as history.
     */
    fun onRetry(messageId: Long) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        val userIndex = messages.subList(0, maxOf(index, 0)).indexOfLast { it.role == LlmMessage.Role.USER }
        if (_uiState.value.sending || userIndex < 0) return
        val user = messages[userIndex]
        startReply { client ->
            val history = messages.subList(0, userIndex).toHistory()
            val prompt = user.generation?.let { promptBuilder.generate(history, it) }
                ?: promptBuilder.chat(history, user.text)
            // The new reply takes the old one's place, in the chat and in storage.
            _uiState.update {
                it.copy(
                    messages = it.messages.map { m ->
                        if (m.id == messageId) ChatMessage(messageId, LlmMessage.Role.ASSISTANT, "", replyState = ReplyState.STREAMING) else m
                    },
                    streamingMessageId = messageId,
                )
            }
            persist(messageId)
            reply(client, prompt, messageId, user.generation)
        }
    }

    /** Saves [recipe] to the library; saving it again replaces it rather than adding a copy. */
    fun onSave(recipe: Recipe) = save(listOf(recipe))

    /** Saves the recipes of one full-day set that are not saved yet. */
    fun onSaveAll(set: List<Recipe>) = save(set.filterNot { it.id in _uiState.value.savedIds })

    fun onSaveFailedDismissed() {
        _uiState.update { it.copy(saveFailed = false) }
    }

    private fun save(toSave: List<Recipe>) {
        viewModelScope.launch {
            val failed = try {
                recipes.save(toSave)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                true
            }
            _uiState.update { state ->
                state.copy(
                    saveFailed = failed,
                    savedVersions = if (failed) state.savedVersions else state.savedVersions + toSave.associateBy { it.id },
                )
            }
        }
    }

    /** Opens the Modify with AI sheet on the recipe [recipeId] of the reply [messageId]; not while a change runs. */
    fun onModify(messageId: Long, recipeId: String) {
        if (_uiState.value.modify?.running == true) return
        _uiState.update { it.copy(modify = ModifyState(messageId, recipeId)) }
    }

    fun onModifyInstructionChanged(text: String) {
        _uiState.update { state -> state.copy(modify = state.modify?.copy(instruction = text)) }
    }

    /** Sends the instruction; a good reply replaces only that card, and a failure leaves it as it was. */
    fun onModifySend() {
        val modify = _uiState.value.modify ?: return
        val recipe = recipe(modify.messageId, modify.recipeId)
        if (!modify.canSend || recipe == null) return
        _uiState.update { it.copy(modify = modify.copy(running = true, error = null)) }
        modifyJob = viewModelScope.launch {
            val result = recipeModifier.modify(recipe, modify.instruction) { seconds ->
                _uiState.update { state -> state.copy(modify = state.modify?.copy(retryingInSeconds = seconds)) }
            }
            when (result) {
                is ModifyResult.Success -> {
                    _uiState.update { state ->
                        state.replaceRecipe(modify.messageId, result.recipe).copy(
                            modify = null,
                            history = state.history + (recipe.id to state.history[recipe.id].orEmpty() + recipe),
                        )
                    }
                    persist(modify.messageId)
                }
                is ModifyResult.Failure -> _uiState.update { state ->
                    state.copy(modify = state.modify?.copy(running = false, error = result.error, retryingInSeconds = null))
                }
            }
        }
    }

    /** Closes the sheet, stopping a change that is still running; the card stays as it was. */
    fun onModifyCancel() {
        modifyJob?.cancel()
        modifyJob = null
        _uiState.update { it.copy(modify = null) }
    }

    /** Puts back the version of the recipe [recipeId] from before its last change. */
    fun onUndoModify(messageId: Long, recipeId: String) {
        val state = _uiState.value
        val previous = state.history[recipeId]?.lastOrNull()
        if (previous == null || state.isModifying(recipeId)) return
        _uiState.update {
            it.replaceRecipe(messageId, previous).copy(history = it.history + (recipeId to it.history.getValue(recipeId).dropLast(1)))
        }
        persist(messageId)
    }

    private fun recipe(messageId: Long, recipeId: String) =
        message(messageId)?.recipes?.firstOrNull { it.id == recipeId }

    /** [recipe] in place of the recipe with its id in the reply [messageId]; the other recipes stay as they are. */
    private fun ChatUiState.replaceRecipe(messageId: Long, recipe: Recipe) = copy(
        messages = messages.map { message ->
            if (message.id != messageId) {
                message
            } else {
                message.copy(recipes = message.recipes.map { if (it.id == recipe.id) recipe else it })
            }
        },
    )

    /** Cancelling the collector cancels the HTTP call; the partial reply stays visible. */
    fun onStop() {
        replyJob?.cancel()
        replyJob = null
        endStream()
        finishReply(stopped = true)
    }

    private fun startReply(block: suspend (LlmClient) -> Unit) {
        _uiState.update { it.copy(sending = true, error = null) }
        replyJob = viewModelScope.launch {
            when (val result = clientFactory.forActiveProvider()) {
                // The input is kept so the user can send it again after fixing Settings.
                is LlmClientResult.Unavailable -> _uiState.update { it.copy(sending = false, error = result.kind) }
                is LlmClientResult.Ready -> {
                    block(result.client)
                    finishReply()
                }
            }
        }
    }

    /** Adds [user] and an empty reply that receives the deltas; returns the reply's id. */
    private fun addTurn(user: ChatMessage, alsoChange: (ChatUiState) -> ChatUiState): Long {
        val replyId = nextId++
        _uiState.update {
            alsoChange(it).copy(
                messages = it.messages + user + ChatMessage(replyId, LlmMessage.Role.ASSISTANT, "", replyState = ReplyState.STREAMING),
                streamingMessageId = replyId,
            )
        }
        persist(user)
        return replyId
    }

    private fun updateComposer(transform: (ComposerState) -> ComposerState) {
        _uiState.update { it.copy(composer = transform(it.composer)) }
    }

    private fun saveDraft(input: String, composer: ComposerState) {
        savedState[KEY_INPUT] = input
        savedState[KEY_SCOPE] = composer.scope.name
        savedState[KEY_COUNT] = composer.count
        savedState[KEY_PREFERENCES] = composer.preferences
        savedState[KEY_EXPANDED] = composer.expanded
    }

    /** The composer as it was saved before the process was stopped, or null when nothing was saved. */
    private fun restoredComposer(): ComposerState? {
        val scope = savedState.get<String>(KEY_SCOPE)?.let { name -> GenerationScope.entries.firstOrNull { it.name == name } }
            ?: return null
        val defaults = ComposerState()
        return ComposerState(
            scope = scope,
            count = (savedState.get<Int>(KEY_COUNT) ?: defaults.count).coerceIn(GenerationRequest.COUNT_RANGE),
            preferences = savedState[KEY_PREFERENCES] ?: defaults.preferences,
            expanded = savedState[KEY_EXPANDED] ?: defaults.expanded,
        )
    }

    /** Streams the reply to [prompt]; [request] is the composer request it answers, null for free-form chat. */
    private suspend fun reply(client: LlmClient, prompt: Prompt, replyId: Long, request: GenerationRequest?) {
        replyBuffer = ReplyBuffer(replyId)
        val error = client.collectReply(prompt.request, ::onRetryWait) { delta -> appendDelta(replyId, delta) }
        endStream()
        if (error != null) {
            _uiState.update { it.copy(error = error) }
            return
        }
        val reply = message(replyId)?.text ?: return
        when (val result = parser.parse(reply)) {
            is RecipeParseResult.Success -> showRecipes(replyId, result, prompt.language, request)
            is RecipeParseResult.Failure ->
                if (result.reason == RecipeParseResult.Reason.NO_JSON) {
                    updateMessage(replyId) { it.copy(shownText = it.text, recipeStatus = RecipeStatus.NONE) }
                } else {
                    repair(client, replyId, reply, result, prompt.language, request)
                }
        }
    }

    /** The one automatic repair turn; its reply is never shown, only its recipes. */
    private suspend fun repair(
        client: LlmClient,
        replyId: Long,
        reply: String,
        failure: RecipeParseResult.Failure,
        language: Language,
        request: GenerationRequest?,
    ) {
        updateMessage(replyId) { it.copy(recipeStatus = RecipeStatus.REPAIRING) }
        val prompt = promptBuilder.repair(reply, failure.description, language)
        val repaired = StringBuilder()
        val error = client.collectReply(prompt.request, ::onRetryWait) { repaired.append(it) }
        val result = if (error == null) parser.parse(repaired.toString()) else null
        if (result is RecipeParseResult.Success) {
            showRecipes(replyId, result.copy(prose = parser.streaming(reply).prose), language, request)
        } else {
            updateMessage(replyId) { it.copy(shownText = it.text, recipeStatus = RecipeStatus.FAILED) }
            if (error != null) _uiState.update { it.copy(error = error) }
        }
    }

    private suspend fun showRecipes(
        replyId: Long,
        result: RecipeParseResult.Success,
        language: Language,
        request: GenerationRequest?,
    ) {
        val grouping = when {
            request == null -> SetGrouping.NUMBERED
            request.isFullDaySet -> SetGrouping.FULL_DAY
            else -> SetGrouping.NONE
        }
        val recipes = assembler.assemble(result.batch, language, nutrition.current(), grouping).recipes
        // A short reply is kept as it is, with a warning; asking again is up to the user.
        val shortfall = request?.shortfall(recipes)
        updateMessage(replyId) {
            it.copy(shownText = result.prose, recipes = recipes, recipeStatus = RecipeStatus.NONE, shortfall = shortfall)
        }
    }

    /**
     * Adds [delta] to the streaming reply. The first delta shows at once; later ones are shown
     * together every [STREAM_UPDATE_MILLIS], so a long reply does not rebuild the chat for every
     * few characters.
     */
    private fun appendDelta(replyId: Long, delta: String) {
        val buffer = replyBuffer?.takeIf { it.replyId == replyId } ?: return
        buffer.append(delta)
        replyDirty = true
        if (updateJob == null) {
            showStream()
            updateJob = viewModelScope.launch {
                do {
                    delay(STREAM_UPDATE_MILLIS)
                } while (showStream())
                updateJob = null
            }
        }
        persistPartial(replyId)
    }

    /** Shows the streaming reply's text if it changed since it was last shown; false when it had not. */
    private fun showStream(): Boolean {
        val buffer = replyBuffer
        if (!replyDirty || buffer == null) return false
        replyDirty = false
        updateMessage(buffer.replyId) { it.streamed(buffer) }
        return true
    }

    /** This reply with the text that has streamed into [buffer]. */
    private fun ChatMessage.streamed(buffer: ReplyBuffer): ChatMessage {
        val (text, streaming) = buffer.streaming()
        return copy(
            text = text,
            shownText = streaming.prose,
            recipeStatus = if (streaming.hasJson) RecipeStatus.BUILDING else RecipeStatus.NONE,
        )
    }

    /** The stream ended: everything that arrived is shown, and the buffer is let go. */
    private fun endStream() {
        updateJob?.cancel()
        updateJob = null
        showStream()
        replyBuffer = null
    }

    private fun onRetryWait(seconds: Int?) {
        _uiState.update { it.copy(retryingInSeconds = seconds) }
    }

    private fun message(id: Long) = _uiState.value.messages.firstOrNull { it.id == id }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state -> state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it }) }
    }

    private fun List<ChatMessage>.toHistory() = map { LlmMessage(it.role, it.text) }

    /** Ends the streaming reply as done, stopped or failed, and stores it. */
    private fun finishReply(stopped: Boolean = false) {
        val before = _uiState.value
        val replyId = before.streamingMessageId
        val end = when {
            stopped -> ReplyState.STOPPED
            before.error != null -> ReplyState.ERROR
            else -> ReplyState.DONE
        }
        _uiState.update { state ->
            state.copy(
                // An empty reply bubble says nothing, so it is dropped; the error explains what happened.
                messages = state.messages
                    .filterNot { it.id == replyId && it.text.isEmpty() }
                    .map { if (it.id == replyId) it.cutOff(end) else it },
                sending = false,
                streamingMessageId = null,
                retryingInSeconds = null,
            )
        }
        if (replyId != null) persist(replyId)
    }

    /** Stops whatever is running and starts an empty chat; the stored conversations stay as they are. */
    private fun resetChat(keepRecentOpen: Boolean = false) {
        if (_uiState.value.sending) onStop()
        modifyJob?.cancel()
        modifyJob = null
        session = Session()
        nextId = 0
        _uiState.update {
            ChatUiState(
                composer = it.composer.copy(expanded = true),
                savedIds = it.savedIds,
                conversations = it.conversations,
                showRecent = keepRecentOpen && it.showRecent,
                pendingDelete = it.pendingDelete,
            )
        }
    }

    /** Shows the stored conversation [id] in [target], unless the user has moved on or started typing turns meanwhile. */
    private suspend fun load(target: Session, id: Long, keepComposer: Boolean = false) {
        val messages = chats.open(id)
        val saved = try {
            recipes.saved(messages.flatMap { m -> m.recipes.map { it.id } })
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            emptyMap() // Cards then show Save, or Saved from the saved ids, without Save changes.
        }
        if (target !== session || _uiState.value.messages.isNotEmpty()) return
        target.conversationId = id
        nextId = (messages.maxOfOrNull { it.id } ?: -1) + 1
        _uiState.update {
            it.copy(
                messages = messages,
                conversationId = id,
                savedVersions = saved,
                // A restored chat shows its replies; the composer folds away as it does after a request.
                composer = if (keepComposer) it.composer else it.composer.copy(expanded = messages.isEmpty()),
            )
        }
    }

    private fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    /** Stores [message] in the current chat, starting the conversation with it if it is the first. */
    private fun persist(message: ChatMessage) {
        val target = session
        enqueue { store(target, message) }
    }

    /** Stores the message [id] of the current chat as it is now, if it is still there. */
    private fun persist(id: Long) {
        message(id)?.let { persist(it) }
    }

    /**
     * Queues a write of the streaming reply [replyId], unless one is already queued. The write
     * takes the text that has arrived by the time it runs, so a reply cut off by the app stopping
     * keeps most of its text without a write for every delta.
     */
    private fun persistPartial(replyId: Long) {
        if (partialWriteQueued) return
        partialWriteQueued = true
        val target = session
        enqueue {
            partialWriteQueued = false
            // The text that has arrived, which the screen may not show yet.
            val reply = message(replyId)?.let { m -> replyBuffer?.takeIf { it.replyId == replyId }?.let { m.streamed(it) } ?: m }
            if (target === session && reply?.replyState == ReplyState.STREAMING) store(target, reply)
        }
    }

    private suspend fun store(target: Session, message: ChatMessage) {
        val id = target.conversationId
        when {
            id != null -> chats.save(id, message)
            // A conversation starts with a user turn; a reply whose turn could not be stored stays in memory.
            message.role == LlmMessage.Role.USER -> {
                val started = chats.start(message)
                target.conversationId = started
                if (target === session) _uiState.update { it.copy(conversationId = started) }
            }
        }
    }

    private companion object {
        /** The streaming reply is shown at most this often, which still reads as smooth typing. */
        const val STREAM_UPDATE_MILLIS = 50L

        const val KEY_INPUT = "chat.input"
        const val KEY_SCOPE = "chat.composer.scope"
        const val KEY_COUNT = "chat.composer.count"
        const val KEY_PREFERENCES = "chat.composer.preferences"
        const val KEY_EXPANDED = "chat.composer.expanded"
    }
}
