package com.elio.recipecraft.data.llm

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Plumbing shared by the wire-protocol clients. Nothing here reads or keeps an error body.

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal val LlmMessage.Role.wireName: String
    get() = when (this) {
        LlmMessage.Role.USER -> "user"
        LlmMessage.Role.ASSISTANT -> "assistant"
    }

/** Maps a transport failure or an HTTP error status to an error kind; never reads the body. */
internal fun errorKind(t: Throwable?, response: Response?): LlmErrorKind = when {
    response != null && !response.isSuccessful -> statusErrorKind(response.code)
    t is InterruptedIOException -> LlmErrorKind.Timeout
    t is IOException -> LlmErrorKind.Network
    // A successful status whose body is not an event stream.
    response != null -> LlmErrorKind.MalformedResponse
    else -> LlmErrorKind.Network
}

private fun statusErrorKind(code: Int): LlmErrorKind = when (code) {
    401, 403 -> LlmErrorKind.InvalidKey
    // 529 is Anthropic's "overloaded": like a rate limit, waiting and retrying is the fix.
    429, OVERLOADED_STATUS -> LlmErrorKind.RateLimited
    else -> LlmErrorKind.Server(code)
}

/**
 * Maps an error a provider reports inside a stream, by its `type` and `code` fields, to an
 * error kind. Only these identifiers are read; the provider's message text is ignored.
 */
internal fun providerErrorKind(type: String?, code: String?): LlmErrorKind {
    // The code is the more specific of the two, e.g. OpenAI's invalid_request_error + invalid_api_key.
    val ids = listOfNotNull(code, type).map { it.lowercase() }
    val status = ids.firstNotNullOfOrNull { PROVIDER_ERROR_STATUS[it] ?: it.toIntOrNull()?.takeIf { s -> s in 400..599 } }
    return status?.let(::statusErrorKind) ?: LlmErrorKind.MalformedResponse
}

private const val OVERLOADED_STATUS = 529

/** Error identifiers used by Anthropic and OpenAI-compatible APIs, and the HTTP status each stands for. */
private val PROVIDER_ERROR_STATUS = mapOf(
    "invalid_request_error" to 400,
    "authentication_error" to 401,
    "invalid_api_key" to 401,
    "billing_error" to 402,
    "permission_error" to 403,
    "not_found_error" to 404,
    "request_too_large" to 413,
    "rate_limit_error" to 429,
    "rate_limit_exceeded" to 429,
    "insufficient_quota" to 429,
    "api_error" to 500,
    "server_error" to 500,
    "timeout_error" to 504,
    "overloaded_error" to OVERLOADED_STATUS,
)

/** Appends [path] to the base URL's path, so `https://host/v1` becomes `https://host/v1/models`. */
internal fun HttpUrl.resolvePath(path: String): HttpUrl {
    val builder = newBuilder()
    if (pathSegments.lastOrNull() == "") builder.removePathSegment(pathSegments.size - 1)
    return builder.addPathSegments(path).build()
}

/** Runs a models-list [request] whose response is `{"data": [{"id": ...}]}`, the shape both protocols use. */
internal suspend fun fetchModelIds(httpClient: OkHttpClient, request: Request, json: Json): ModelListResult {
    val call = httpClient.newCall(request)
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(ModelListResult.Failure(errorKind(e, null)))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response.use { parseModels(it, json) })
            }
        })
    }
}

private fun parseModels(response: Response, json: Json): ModelListResult {
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
private data class ModelList(val data: List<ModelEntry> = emptyList())

@Serializable
private data class ModelEntry(val id: String = "")
