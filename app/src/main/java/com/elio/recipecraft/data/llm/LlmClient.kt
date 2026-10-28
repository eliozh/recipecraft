package com.elio.recipecraft.data.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile

/** One chat turn sent to the model. */
data class LlmMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

/** A request for one reply; the model comes from the client's provider config. */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val system: String? = null,
)

/** The outcome of listing the models a key can use. */
sealed interface ModelListResult {
    data class Success(val models: List<String>) : ModelListResult
    data class Failure(val kind: LlmErrorKind) : ModelListResult
}

/** A provider-neutral LLM connection; each wire protocol has its own implementation. */
interface LlmClient {
    /**
     * Streams one reply. The flow ends after a [LlmEvent.Done] or [LlmEvent.Error];
     * cancelling the collector cancels the HTTP request.
     */
    fun stream(request: LlmRequest): Flow<LlmEvent>

    /** The model ids the provider offers, sorted. */
    suspend fun listModels(): ModelListResult
}

/**
 * Collects one reply from [stream]: [onDelta] gets each piece of text as it arrives. While a
 * rate-limited request waits to be sent again, [onRetryWait] gets the wait in whole seconds, then
 * null once the next attempt answers. Returns the error that ended the reply, or null when it
 * finished. A stream that stops without a [LlmEvent.Done] or [LlmEvent.Error] lost its end, so it
 * counts as a [LlmErrorKind.Network] error rather than a finished reply.
 */
suspend fun LlmClient.collectReply(
    request: LlmRequest,
    onRetryWait: (Int?) -> Unit = {},
    onDelta: (String) -> Unit,
): LlmErrorKind? {
    var error: LlmErrorKind? = LlmErrorKind.Network
    var waiting = false
    stream(request)
        .takeWhile { event ->
            if (waiting && event !is LlmEvent.Retrying) {
                waiting = false
                onRetryWait(null)
            }
            when (event) {
                is LlmEvent.Delta -> {
                    onDelta(event.text)
                    true
                }
                is LlmEvent.Retrying -> {
                    waiting = true
                    onRetryWait(event.delaySeconds)
                    true
                }
                is LlmEvent.Done -> {
                    error = null
                    false
                }
                is LlmEvent.Error -> {
                    error = event.kind
                    false
                }
            }
        }
        .collect {}
    return error
}
