package com.elio.recipecraft.data.llm

/** What a streaming reply produces; failures are values so every caller has to handle them. */
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Done(val usage: Usage?) : LlmEvent
    /**
     * The request failed. [retryAfterMillis] is how long the provider asked the client to wait,
     * from a `Retry-After` header, when it sent one.
     */
    data class Error(val kind: LlmErrorKind, val retryAfterMillis: Long? = null) : LlmEvent

    /**
     * The provider rate-limited the request before any reply text arrived, and it is sent again
     * after [delayMillis]; [attempt] is the attempt that failed, from 1. Only [RetryingLlmClient]
     * emits it, and only before any [Delta].
     */
    data class Retrying(val attempt: Int, val delayMillis: Long) : LlmEvent {
        /** The wait in whole seconds, rounded up, as the screen shows it. */
        val delaySeconds: Int get() = ((delayMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()

        private companion object {
            const val MILLIS_PER_SECOND = 1000L
        }
    }
}

/** Token counts, when the provider reports them. */
data class Usage(val inputTokens: Int?, val outputTokens: Int?)

/** Why a request failed or could not be made. Carries no response body, so it is safe to show or log. */
sealed interface LlmErrorKind {
    /** The provider rejected the key (401/403). */
    data object InvalidKey : LlmErrorKind

    /** Too many requests or quota exhausted (429). */
    data object RateLimited : LlmErrorKind

    /** The connection could not be made or broke before the reply finished. */
    data object Network : LlmErrorKind

    /** The provider stopped responding within the time limit. */
    data object Timeout : LlmErrorKind

    /** Any other HTTP failure. */
    data class Server(val statusCode: Int) : LlmErrorKind

    /** A successful response that could not be understood. */
    data object MalformedResponse : LlmErrorKind

    /** No API key is saved for the active provider. */
    data object MissingKey : LlmErrorKind

    /** The active provider has no model selected. */
    data object MissingModel : LlmErrorKind

    /** The base URL is missing, invalid or not https. */
    data object InvalidEndpoint : LlmErrorKind

    /** The Android Keystore could not open the saved key; not the same as having no key. */
    data object KeyStoreUnavailable : LlmErrorKind

    /** The saved settings could not be read. */
    data object SettingsUnavailable : LlmErrorKind
}
