package com.elio.recipecraft.data.llm

import kotlinx.coroutines.flow.Flow

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
