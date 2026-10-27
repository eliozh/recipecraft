package com.elio.recipecraft.data.llm

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
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

        val listener = object : EventSourceListener() {
            private var usage: Usage? = null
            private var finished = false

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == DONE_MARKER) return finish(LlmEvent.Done(usage))
                val chunk = try {
                    json.decodeFromString(Chunk.serializer(), data)
                } catch (_: SerializationException) {
                    return finish(LlmEvent.Error(LlmErrorKind.MalformedResponse))
                } catch (_: IllegalArgumentException) {
                    return finish(LlmEvent.Error(LlmErrorKind.MalformedResponse))
                }
                chunk.usage?.let { usage = Usage(it.promptTokens, it.completionTokens) }
                chunk.choices.forEach { choice ->
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { trySend(LlmEvent.Delta(it)) }
                    if (choice.finishReason != null) finished = true
                }
            }

            // A stream that ends without [DONE] is complete only if the model said it finished.
            override fun onClosed(eventSource: EventSource) = finish(
                if (finished) LlmEvent.Done(usage) else LlmEvent.Error(LlmErrorKind.Network),
            )

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                finish(LlmEvent.Error(errorKind(t, response)))

            private fun finish(event: LlmEvent) {
                trySend(event)
                channel.close()
            }
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
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(ModelListResult.Failure(errorKind(e, null)))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response.use { parseModels(it) })
                }
            })
        }
    }

    private fun parseModels(response: Response): ModelListResult {
        if (!response.isSuccessful) return ModelListResult.Failure(errorKind(null, response))
        return try {
            val list = json.decodeFromString(ModelList.serializer(), response.body.string())
            ModelListResult.Success(list.data.map { it.id }.filter { it.isNotBlank() }.distinct().sorted())
        } catch (_: IOException) {
            ModelListResult.Failure(LlmErrorKind.Network)
        } catch (_: SerializationException) {
            ModelListResult.Failure(LlmErrorKind.MalformedResponse)
        } catch (_: IllegalArgumentException) {
            ModelListResult.Failure(LlmErrorKind.MalformedResponse)
        }
    }

    @Serializable
    private data class ChatRequest(val model: String, val messages: List<WireMessage>, val stream: Boolean)

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class Chunk(val choices: List<Choice> = emptyList(), val usage: WireUsage? = null)

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

    @Serializable
    private data class ModelList(val data: List<ModelEntry> = emptyList())

    @Serializable
    private data class ModelEntry(val id: String = "")

    private companion object {
        const val DONE_MARKER = "[DONE]"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val LlmMessage.Role.wireName: String
            get() = when (this) {
                LlmMessage.Role.USER -> "user"
                LlmMessage.Role.ASSISTANT -> "assistant"
            }

        /** Appends [path] to the base URL's path, so `https://host/v1` becomes `https://host/v1/models`. */
        fun HttpUrl.resolvePath(path: String): HttpUrl {
            val builder = newBuilder()
            if (pathSegments.lastOrNull() == "") builder.removePathSegment(pathSegments.size - 1)
            return builder.addPathSegments(path).build()
        }
    }
}

/** Maps a transport failure or an HTTP error status to an error kind; never reads the body. */
internal fun errorKind(t: Throwable?, response: Response?): LlmErrorKind = when {
    response != null && !response.isSuccessful -> when (response.code) {
        401, 403 -> LlmErrorKind.InvalidKey
        429 -> LlmErrorKind.RateLimited
        else -> LlmErrorKind.Server(response.code)
    }
    t is InterruptedIOException -> LlmErrorKind.Timeout
    t is IOException -> LlmErrorKind.Network
    // A successful status whose body is not an event stream.
    response != null -> LlmErrorKind.MalformedResponse
    else -> LlmErrorKind.Network
}
