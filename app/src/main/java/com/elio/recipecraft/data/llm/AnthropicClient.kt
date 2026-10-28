package com.elio.recipecraft.data.llm

import java.time.Clock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Talks to the Anthropic Messages API (`/v1/messages`, `/v1/models`) on Claude and on endpoints
 * that copy it. The key goes only into the `x-api-key` header, and response bodies are never
 * logged or put into errors.
 *
 * The base URL is the host root, such as `https://api.anthropic.com`; `/v1` is added unless the
 * base URL already ends with it.
 */
class AnthropicClient(
    baseUrl: HttpUrl,
    private val model: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val json: Json,
    /** Reads a `Retry-After` header that is a date. */
    private val clock: Clock = Clock.systemUTC(),
) : LlmClient {

    private val messagesUrl = baseUrl.versionedPath("messages")
    private val modelsUrl = baseUrl.versionedPath("models").newBuilder()
        .addQueryParameter("limit", MODELS_PAGE_SIZE.toString())
        .build()

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val body = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = request.system,
            messages = request.messages.map { WireMessage(it.role.wireName, it.content) },
            stream = true,
        )
        val httpRequest = Request.Builder()
            .url(messagesUrl)
            .authHeaders(apiKey)
            .post(json.encodeToString(MessagesRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val sink = StreamSink(this)
        val listener = object : EventSourceListener() {
            private var inputTokens: Int? = null
            private var outputTokens: Int? = null

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = try {
                    json.decodeFromString(StreamEvent.serializer(), data)
                } catch (_: SerializationException) {
                    return sink.finish(eventSource, LlmEvent.Error(LlmErrorKind.MalformedResponse))
                } catch (_: IllegalArgumentException) {
                    return sink.finish(eventSource, LlmEvent.Error(LlmErrorKind.MalformedResponse))
                }
                when (event.type) {
                    "message_start" -> event.message?.usage?.let(::record)
                    "content_block_delta" -> event.delta
                        ?.takeIf { it.type == TEXT_DELTA }
                        ?.text?.takeIf { it.isNotEmpty() }
                        ?.let { sink.send(eventSource, LlmEvent.Delta(it)) }
                    "message_delta" -> event.usage?.let(::record)
                    "message_stop" -> sink.finish(eventSource, LlmEvent.Done(usage()))
                    "error" -> sink.finish(eventSource, LlmEvent.Error(providerErrorKind(event.error?.type, null)))
                    // ping, content_block_start/stop, and event types added later carry no reply text.
                    else -> Unit
                }
            }

            // Every complete reply ends with message_stop, so closing before it means the reply was cut off.
            override fun onClosed(eventSource: EventSource) =
                sink.finish(eventSource, LlmEvent.Error(LlmErrorKind.Network))

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                sink.finish(eventSource, LlmEvent.Error(errorKind(t, response), retryAfterMillis(response, clock)))

            private fun record(usage: WireUsage) {
                usage.inputTokens?.let { inputTokens = it }
                usage.outputTokens?.let { outputTokens = it }
            }

            private fun usage(): Usage? =
                if (inputTokens == null && outputTokens == null) null else Usage(inputTokens, outputTokens)
        }
        val eventSource = EventSources.createFactory(httpClient).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun listModels(): ModelListResult {
        val request = Request.Builder()
            .url(modelsUrl)
            .authHeaders(apiKey)
            .get()
            .build()
        return fetchModelIds(httpClient, request, json)
    }

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        // Left out of the JSON when null: encodeDefaults is off.
        val system: String? = null,
        val messages: List<WireMessage>,
        val stream: Boolean,
    )

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class StreamEvent(
        val type: String = "",
        val message: StartedMessage? = null,
        val delta: EventDelta? = null,
        val usage: WireUsage? = null,
        val error: WireError? = null,
    )

    @Serializable
    private data class StartedMessage(val usage: WireUsage? = null)

    @Serializable
    private data class EventDelta(val type: String? = null, val text: String? = null)

    @Serializable
    private data class WireUsage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )

    /** Only the error type is decoded; the provider's `message` is never read. */
    @Serializable
    private data class WireError(val type: String? = null)

    private companion object {
        /** The Messages API version this client speaks. */
        const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * The reply's output cap. A batch of up to ten recipes as JSON, plus any thinking the
         * model does first, fits well inside it; hitting it would cut the JSON off.
         */
        const val MAX_TOKENS = 32_000

        /** Large enough that one page holds every model a provider lists. */
        const val MODELS_PAGE_SIZE = 1000

        const val TEXT_DELTA = "text_delta"

        fun Request.Builder.authHeaders(apiKey: String): Request.Builder = this
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)

        /** `https://host` and `https://host/v1` both become `https://host/v1/<endpoint>`. */
        fun HttpUrl.versionedPath(endpoint: String): HttpUrl =
            if (pathSegments.lastOrNull { it.isNotEmpty() } == "v1") resolvePath(endpoint) else resolvePath("v1/$endpoint")
    }
}
