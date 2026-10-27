package com.elio.recipecraft.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far an assistant reply has got with turning its JSON into recipes. */
enum class RecipeStatus {
    /** Plain text, or recipes already parsed. */
    NONE,

    /** JSON is streaming in; the bubble shows only the prose. */
    BUILDING,

    /** The JSON did not parse and the one automatic repair turn is running. */
    REPAIRING,

    /** Neither the reply nor the repair parsed; the raw text is shown with Retry. */
    FAILED,
}

data class ChatMessage(
    val id: Long,
    val role: LlmMessage.Role,
    /** The raw text, sent back to the model as history. */
    val text: String,
    /** What the bubble shows: the prose without the JSON block while recipes are involved. */
    val shownText: String = text,
    val recipes: List<Recipe> = emptyList(),
    val recipeStatus: RecipeStatus = RecipeStatus.NONE,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    /** True from Send until the reply finishes, fails or is stopped. */
    val sending: Boolean = false,
    /** The id of the assistant message that is receiving deltas. */
    val streamingMessageId: Long? = null,
    val error: LlmErrorKind? = null,
) {
    val canSend: Boolean get() = !sending && input.isNotBlank()
}

/** A free-form chat kept in memory; persistence comes with the chat history task. */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val clientFactory: LlmClientFactory,
    private val promptBuilder: PromptBuilder,
    private val parser: RecipeParser,
    private val assembler: RecipeAssembler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var nextId = 0L
    private var replyJob: Job? = null

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return
        val text = state.input.trim()
        startReply { client ->
            val history = _uiState.value.messages.toHistory()
            val userMessage = ChatMessage(nextId++, LlmMessage.Role.USER, text)
            val replyId = nextId++
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage + ChatMessage(replyId, LlmMessage.Role.ASSISTANT, ""),
                    input = "",
                    streamingMessageId = replyId,
                )
            }
            generate(client, history, text, replyId)
        }
    }

    /** Asks again for the reply [messageId], replacing it; the user turn before it is resent. */
    fun onRetry(messageId: Long) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        val userIndex = messages.subList(0, maxOf(index, 0)).indexOfLast { it.role == LlmMessage.Role.USER }
        if (_uiState.value.sending || userIndex < 0) return
        startReply { client ->
            val replyId = nextId++
            _uiState.update {
                it.copy(
                    messages = it.messages.filterNot { m -> m.id == messageId } +
                        ChatMessage(replyId, LlmMessage.Role.ASSISTANT, ""),
                    streamingMessageId = replyId,
                )
            }
            generate(client, messages.subList(0, userIndex).toHistory(), messages[userIndex].text, replyId)
        }
    }

    /** Cancelling the collector cancels the HTTP call; the partial reply stays visible. */
    fun onStop() {
        replyJob?.cancel()
        replyJob = null
        finishReply()
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

    private suspend fun generate(client: LlmClient, history: List<LlmMessage>, text: String, replyId: Long) {
        val prompt = promptBuilder.chat(history, text)
        val error = client.collectReply(prompt.request) { delta -> appendDelta(replyId, delta) }
        if (error != null) {
            _uiState.update { it.copy(error = error) }
            return
        }
        val reply = message(replyId)?.text ?: return
        when (val result = parser.parse(reply)) {
            is RecipeParseResult.Success -> showRecipes(replyId, result, prompt.language)
            is RecipeParseResult.Failure ->
                if (result.reason == RecipeParseResult.Reason.NO_JSON) {
                    updateMessage(replyId) { it.copy(shownText = it.text, recipeStatus = RecipeStatus.NONE) }
                } else {
                    repair(client, replyId, reply, result, prompt.language)
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
    ) {
        updateMessage(replyId) { it.copy(recipeStatus = RecipeStatus.REPAIRING) }
        val prompt = promptBuilder.repair(reply, failure.description, language)
        val repaired = StringBuilder()
        val error = client.collectReply(prompt.request) { repaired.append(it) }
        val result = if (error == null) parser.parse(repaired.toString()) else null
        if (result is RecipeParseResult.Success) {
            showRecipes(replyId, result.copy(prose = parser.streaming(reply).prose), language)
        } else {
            updateMessage(replyId) { it.copy(shownText = it.text, recipeStatus = RecipeStatus.FAILED) }
            if (error != null) _uiState.update { it.copy(error = error) }
        }
    }

    private fun showRecipes(replyId: Long, result: RecipeParseResult.Success, language: Language) {
        val recipes = assembler.assemble(result.batch, language).recipes
        updateMessage(replyId) {
            it.copy(shownText = result.prose, recipes = recipes, recipeStatus = RecipeStatus.NONE)
        }
    }

    private fun appendDelta(replyId: Long, delta: String) = updateMessage(replyId) {
        val text = it.text + delta
        val streaming = parser.streaming(text)
        it.copy(
            text = text,
            shownText = streaming.prose,
            recipeStatus = if (streaming.hasJson) RecipeStatus.BUILDING else RecipeStatus.NONE,
        )
    }

    /** Collects one reply; returns the error that ended it, or null when it finished. */
    private suspend fun LlmClient.collectReply(request: LlmRequest, onDelta: (String) -> Unit): LlmErrorKind? {
        var error: LlmErrorKind? = null
        stream(request)
            .takeWhile { event ->
                when (event) {
                    is LlmEvent.Delta -> {
                        onDelta(event.text)
                        true
                    }
                    is LlmEvent.Done -> false
                    is LlmEvent.Error -> {
                        error = event.kind
                        false
                    }
                }
            }
            .collect {}
        return error
    }

    private fun message(id: Long) = _uiState.value.messages.firstOrNull { it.id == id }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state -> state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it }) }
    }

    private fun List<ChatMessage>.toHistory() = map { LlmMessage(it.role, it.text) }

    private fun finishReply() {
        _uiState.update { state ->
            state.copy(
                // An empty reply bubble says nothing, so it is dropped; the error explains what happened.
                messages = state.messages
                    .filterNot { it.id == state.streamingMessageId && it.text.isEmpty() }
                    .map {
                        // A reply cut off while its recipes were still coming can only be retried.
                        if (it.id == state.streamingMessageId && it.recipeStatus != RecipeStatus.NONE) {
                            it.copy(shownText = it.text, recipeStatus = RecipeStatus.FAILED)
                        } else {
                            it
                        }
                    },
                sending = false,
                streamingMessageId = null,
            )
        }
    }
}
