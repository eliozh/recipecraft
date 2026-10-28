package com.elio.recipecraft.data.llm

import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * When a rate-limited request is sent again: at most [maxAttempts] attempts in all, waiting an
 * exponential backoff with jitter between them, or as long as the provider's `Retry-After` asks.
 */
class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val random: Random = Random.Default,
) {

    @Inject
    constructor() : this(DEFAULT_MAX_ATTEMPTS)

    init {
        require(maxAttempts >= 1 && baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis)
    }

    /**
     * The wait before sending again after attempt [attempt] (from 1) was rate-limited, or null
     * when it should not be sent again: no attempt is left, or the provider asks for a wait longer
     * than the policy's maximum, which the user is better off knowing about than watching.
     */
    fun delayMillis(attempt: Int, retryAfterMillis: Long?): Long? {
        if (attempt >= maxAttempts) return null
        if (retryAfterMillis != null) return retryAfterMillis.takeIf { it in 0..maxDelayMillis }
        val backoff = (baseDelayMillis shl (attempt - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMillis)
        // Equal jitter: half the backoff is fixed and half random, so clients limited together spread out.
        val half = backoff / 2
        return backoff - half + random.nextLong(half + 1)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BASE_DELAY_MILLIS = 2_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 30_000L

        /** Keeps the shift from overflowing; the backoff is capped long before it. */
        private const val MAX_SHIFT = 20
    }
}

/**
 * Sends a rate-limited request again, as [policy] allows, before any reply text has arrived:
 * a reply that has started streaming is never sent again, so no text is duplicated. While it waits
 * it emits [LlmEvent.Retrying], so the screen can say so; when the attempts run out, the last
 * rate-limit error ends the flow as usual.
 */
class RetryingLlmClient(
    val delegate: LlmClient,
    private val policy: RetryPolicy,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        var attempt = 1
        while (true) {
            var replied = false
            var limited: LlmEvent.Error? = null
            delegate.stream(request).collect { event ->
                if (event is LlmEvent.Error && event.kind == LlmErrorKind.RateLimited && !replied) {
                    limited = event
                } else {
                    if (event is LlmEvent.Delta) replied = true
                    emit(event)
                }
            }
            val error = limited ?: return@flow
            val wait = policy.delayMillis(attempt, error.retryAfterMillis)
            if (wait == null) {
                emit(error)
                return@flow
            }
            emit(LlmEvent.Retrying(attempt, wait))
            sleep(wait)
            attempt++
        }
    }

    override suspend fun listModels(): ModelListResult = delegate.listModels()
}
