package com.elio.recipecraft.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val role: LlmMessage.Role,
    val text: String,
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
        _uiState.update { it.copy(sending = true, error = null) }
        replyJob = viewModelScope.launch {
            val client = when (val result = clientFactory.forActiveProvider()) {
                is LlmClientResult.Unavailable -> {
                    // The input is kept so the user can send it again after fixing Settings.
                    _uiState.update { it.copy(sending = false, error = result.kind) }
                    return@launch
                }
                is LlmClientResult.Ready -> result.client
            }
            val history = _uiState.value.messages.map { LlmMessage(it.role, it.text) }
            val userMessage = ChatMessage(nextId++, LlmMessage.Role.USER, text)
            val replyId = nextId++
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage + ChatMessage(replyId, LlmMessage.Role.ASSISTANT, ""),
                    input = "",
                    streamingMessageId = replyId,
                )
            }
            val request = LlmRequest(history + LlmMessage(LlmMessage.Role.USER, text))
            client.stream(request)
                .takeWhile { event -> handle(replyId, event) }
                .collect {}
            finishReply()
        }
    }

    /** Cancelling the collector cancels the HTTP call; the partial reply stays visible. */
    fun onStop() {
        replyJob?.cancel()
        replyJob = null
        finishReply()
    }

    /** Returns whether more events are expected. */
    private fun handle(replyId: Long, event: LlmEvent): Boolean = when (event) {
        is LlmEvent.Delta -> {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.id == replyId) it.copy(text = it.text + event.text) else it
                    },
                )
            }
            true
        }
        is LlmEvent.Done -> false
        is LlmEvent.Error -> {
            _uiState.update { it.copy(error = event.kind) }
            false
        }
    }

    private fun finishReply() {
        _uiState.update { state ->
            // An empty reply bubble says nothing, so it is dropped; the error explains what happened.
            state.copy(
                messages = state.messages.filterNot { it.id == state.streamingMessageId && it.text.isEmpty() },
                sending = false,
                streamingMessageId = null,
            )
        }
    }
}
