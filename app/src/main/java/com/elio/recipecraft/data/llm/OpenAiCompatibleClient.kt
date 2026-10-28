package com.elio.recipecraft.data.llm

import java.time.Clock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Talks to `/chat/completions` and `/models` on OpenAI, DeepSeek and other endpoints that copy
 * the OpenAI API. The key goes only into the Authorization header, and response bodies are
 * never logged or put into errors.
 */
class OpenAiCompatibleClient(
    baseUrl: HttpUrl,
    private val model: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val json: Json,
    /** Reads a `Retry-After` header that is a date. */
    private val clock: Clock = Clock.systemUTC(),
) : LlmClient {

    private val chatUrl = baseUrl.resolvePath("chat/completions")
    private val modelsUrl = baseUrl.resolvePath("models")

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val body = ChatRequest(
            model = model,
            messages = buildList {
                request.system?.let { add(WireMessage("system", it)) }
                request.messages.forEach { add(WireMessage(it.role.wireName, it.content)) }
            },
            stream = true,
        )
        val httpRequest = Request.Builder()
            .url(chatUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(ChatRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val sink = StreamSink(this)
        val listener = object : EventSourceListener() {
            private var usage: Usage? = null
            private var finished = false

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == DONE_MARKER) return sink.finish(eventSource, LlmEvent.Done(usage))
                val chunk = try {
                    json.decodeFromString(Chunk.serializer(), data)
                } catch (_: SerializationException) {
                    return sink.finish(eventSource, LlmEvent.Error(LlmErrorKind.MalformedResponse))
                } catch (_: IllegalArgumentException) {
                    return sink.finish(eventSource, LlmEvent.Error(LlmErrorKind.MalformedResponse))
                }
                chunk.error?.let { error ->
                    return sink.finish(eventSource, LlmEvent.Error(providerErrorKind(error.type, error.code?.contentOrNull)))
                }
                chunk.usage?.let { usage = Usage(it.promptTokens, it.completionTokens) }
                chunk.choices.forEach { choice ->
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { sink.send(eventSource, LlmEvent.Delta(it)) }
                    if (choice.finishReason != null) finished = true
                }
            }

            // A stream that ends without [DONE] is complete only if the model said it finished.
            override fun onClosed(eventSource: EventSource) = sink.finish(
                eventSource,
                if (finished) LlmEvent.Done(usage) else LlmEvent.Error(LlmErrorKind.Network),
            )

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                sink.finish(eventSource, LlmEvent.Error(errorKind(t, response), retryAfterMillis(response, clock)))
        }
        val eventSource = EventSources.createFactory(httpClient).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun listModels(): ModelListResult {
        val request = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return fetchModelIds(httpClient, request, json)
    }

    @Serializable
    private data class ChatRequest(val model: String, val messages: List<WireMessage>, val stream: Boolean)

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class Chunk(
        val choices: List<Choice> = emptyList(),
        val usage: WireUsage? = null,
        val error: WireError? = null,
    )

    @Serializable
    private data class Choice(
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class Delta(val content: String? = null)

    @Serializable
    private data class WireUsage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
    )

    /** Only the identifiers are decoded; the provider's `message` is never read. `code` may be a string or a number. */
    @Serializable
    private data class WireError(val type: String? = null, val code: JsonPrimitive? = null)

    private companion object {
        const val DONE_MARKER = "[DONE]"
    }
}
