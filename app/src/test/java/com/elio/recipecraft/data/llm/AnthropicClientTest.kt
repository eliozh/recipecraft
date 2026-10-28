package com.elio.recipecraft.data.llm

import app.cash.turbine.test
import com.elio.recipecraft.testing.PLACEHOLDER_KEY
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val callCancelled = CountDownLatch(1)

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) = callCancelled.countDown()
        })
        .build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun client(path: String = "/") =
        AnthropicClient(server.url(path), MODEL, PLACEHOLDER_KEY, httpClient, json)

    private fun sse(body: String) = MockResponse.Builder()
        .setHeader("Content-Type", "text/event-stream")
        .chunkedBody(body, 64)

    private fun sseFixture(name: String) = sse(fixture(name))

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/anthropic/$name")!!.bufferedReader().readText()

    private val request = LlmRequest(
        messages = listOf(
            LlmMessage(LlmMessage.Role.USER, "Breakfast idea?"),
            LlmMessage(LlmMessage.Role.ASSISTANT, "Oatmeal."),
            LlmMessage(LlmMessage.Role.USER, "Something with eggs"),
        ),
        system = "You are a cook.",
    )

    @Test
    fun `streams text deltas in order then done with usage, skipping ping and thinking`() = runTest {
        server.enqueue(sseFixture("stream_normal.txt").build())

        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Delta(" a tomato"), awaitItem())
            assertEquals(LlmEvent.Delta(" omelette."), awaitItem())
            assertEquals(LlmEvent.Done(Usage(inputTokens = 25, outputTokens = 15)), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `sends model, max tokens, top-level system, messages and stream flag with anthropic headers`() = runTest {
        server.enqueue(sseFixture("stream_normal.txt").build())
        client().stream(request).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals(PLACEHOLDER_KEY, recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertEquals(null, recorded.headers["Authorization"])
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals(MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals(32_000, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("You are a cook.", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it as JsonObject }
        assertEquals(
            listOf(
                "user" to "Breakfast idea?",
                "assistant" to "Oatmeal.",
                "user" to "Something with eggs",
            ),
            messages.map { it["role"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `a request without a system prompt leaves the field out`() = runTest {
        server.enqueue(sseFixture("stream_normal.txt").build())
        client().stream(request.copy(system = null)).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val body = json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        assertFalse(body.containsKey("system"))
    }

    @Test
    fun `the version path is added once whether or not the base url has it`() = runTest {
        repeat(4) { server.enqueue(sseFixture("stream_normal.txt").build()) }
        listOf("/", "/anthropic", "/anthropic/v1", "/anthropic/v1/").forEach { path ->
            client(path).stream(request).test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }

        assertEquals("/v1/messages", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        repeat(3) {
            assertEquals("/anthropic/v1/messages", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        }
    }

    @Test
    fun `an error event mid-stream ends the reply with its kind`() = runTest {
        server.enqueue(sseFixture("stream_error.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.RateLimited), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `error event types map to error kinds`() = runTest {
        val cases = listOf(
            "authentication_error" to LlmErrorKind.InvalidKey,
            "permission_error" to LlmErrorKind.InvalidKey,
            "rate_limit_error" to LlmErrorKind.RateLimited,
            "overloaded_error" to LlmErrorKind.RateLimited,
            "api_error" to LlmErrorKind.Server(500),
            "invalid_request_error" to LlmErrorKind.Server(400),
            "something_new" to LlmErrorKind.MalformedResponse,
        )
        cases.forEach { (type, _) ->
            server.enqueue(
                sse("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"x\"}}\n\n").build(),
            )
        }
        cases.forEach { (type, kind) ->
            client().stream(request).test {
                assertEquals(type, LlmEvent.Error(kind), awaitItem())
                awaitComplete()
            }
        }
    }

    @Test
    fun `a stream cut off before message_stop is a network error`() = runTest {
        server.enqueue(sseFixture("stream_truncated.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Delta(" a"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.Network), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a dropped connection mid-stream is a network error`() = runTest {
        server.enqueue(
            sseFixture("stream_normal.txt")
                .throttleBody(400, 50, TimeUnit.MILLISECONDS)
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        client().stream(request).test {
            var event = awaitItem()
            while (event is LlmEvent.Delta) event = awaitItem()
            assertEquals(LlmEvent.Error(LlmErrorKind.Network), event)
            awaitComplete()
        }
    }

    @Test
    fun `a malformed event is reported`() = runTest {
        server.enqueue(sseFixture("stream_malformed.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.MalformedResponse), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `http error statuses map to error kinds`() = runTest {
        val cases = listOf(
            MockResponse.Builder().code(401).body(fixture("error_401.json")).build() to LlmErrorKind.InvalidKey,
            MockResponse.Builder().code(403).body("{}").build() to LlmErrorKind.InvalidKey,
            MockResponse.Builder().code(429).body(fixture("error_429.json")).build() to LlmErrorKind.RateLimited,
            MockResponse.Builder().code(529).body(fixture("error_529.json")).build() to LlmErrorKind.RateLimited,
            MockResponse.Builder().code(500).body("{}").build() to LlmErrorKind.Server(500),
        )
        cases.forEach { (response, _) -> server.enqueue(response) }
        cases.forEach { (_, kind) ->
            client().stream(request).test {
                assertEquals(LlmEvent.Error(kind), awaitItem())
                awaitComplete()
            }
        }
    }

    @Test
    fun `no response in time is a timeout`() = runTest {
        server.enqueue(sseFixture("stream_normal.txt").headersDelay(2, TimeUnit.SECONDS).build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.Timeout), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a slow collector gets every delta in order and the done event`() {
        val words = (1..SLOW_STREAM_EVENTS).map { "w$it " }
        val body = buildString {
            words.forEach {
                append("event: content_block_delta\n")
                append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"$it\"}}\n\n")
            }
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(sse(body).build())

        // Real time, so the reader thread runs far ahead of a collector that is slow on every item.
        val events = runBlocking { client().stream(request).onEach { delay(2) }.toList() }

        assertEquals(words.map { LlmEvent.Delta(it) } + LlmEvent.Done(null), events)
    }

    @Test
    fun `an unreachable host is a network error`() = runTest {
        val url = server.url("/")
        server.close()
        AnthropicClient(url, MODEL, PLACEHOLDER_KEY, httpClient, json).stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.Network), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `cancelling the collector cancels the http call`() = runTest {
        server.enqueue(sseFixture("stream_normal.txt").throttleBody(64, 100, TimeUnit.MILLISECONDS).build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("OkHttp call was not cancelled", callCancelled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `lists model ids sorted and deduplicated`() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture("models.json")).build())

        assertEquals(ModelListResult.Success(listOf("claude-haiku-test", "claude-test")), client().listModels())
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.url.encodedPath)
        assertEquals("1000", recorded.url.queryParameter("limit"))
        assertEquals(PLACEHOLDER_KEY, recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
    }

    @Test
    fun `listing models maps errors`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body(fixture("error_401.json")).build())
        server.enqueue(MockResponse.Builder().body("not json").build())

        assertEquals(ModelListResult.Failure(LlmErrorKind.InvalidKey), client().listModels())
        assertEquals(ModelListResult.Failure(LlmErrorKind.MalformedResponse), client().listModels())
    }

    private companion object {
        /** Well past the 64 events a callbackFlow buffers by default. */
        const val SLOW_STREAM_EVENTS = 300
        const val MODEL = "claude-test"
    }
}
