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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.SocketHandler
import okio.Socket
import okio.buffer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {

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

    private fun client(path: String = "/v1") =
        OpenAiCompatibleClient(server.url(path), MODEL, PLACEHOLDER_KEY, httpClient, json)

    private fun sse(fixture: String) = MockResponse.Builder()
        .setHeader("Content-Type", "text/event-stream")
        .chunkedBody(fixture(fixture), 64)

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/openai/$name")!!.bufferedReader().readText()

    private val request = LlmRequest(
        messages = listOf(
            LlmMessage(LlmMessage.Role.USER, "Breakfast idea?"),
            LlmMessage(LlmMessage.Role.ASSISTANT, "Oatmeal."),
            LlmMessage(LlmMessage.Role.USER, "Something with eggs"),
        ),
        system = "You are a cook.",
    )

    @Test
    fun `streams deltas in order then done with usage`() = runTest {
        server.enqueue(sse("stream_normal.txt").build())

        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Delta(" a tomato"), awaitItem())
            assertEquals(LlmEvent.Delta(" omelette."), awaitItem())
            assertEquals(LlmEvent.Done(Usage(inputTokens = 12, outputTokens = 5)), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `sends the model, messages and stream flag with a bearer key`() = runTest {
        server.enqueue(sse("stream_normal.txt").build())
        client().stream(request).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer $PLACEHOLDER_KEY", recorded.headers["Authorization"])
        assertEquals("text/event-stream", recorded.headers["Accept"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals(MODEL, body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        val messages = body["messages"]!!.jsonArray.map { it as JsonObject }
        assertEquals(
            listOf(
                "system" to "You are a cook.",
                "user" to "Breakfast idea?",
                "assistant" to "Oatmeal.",
                "user" to "Something with eggs",
            ),
            messages.map { it["role"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `a base url with a trailing slash or no version path joins cleanly`() = runTest {
        server.enqueue(sse("stream_normal.txt").build())
        server.enqueue(sse("stream_normal.txt").build())
        client("/v1/").stream(request).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        client("/").stream(request).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("/v1/chat/completions", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals("/chat/completions", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `a stream that ends after a finish reason without DONE completes`() = runTest {
        server.enqueue(sse("stream_chinese.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("试试"), awaitItem())
            assertEquals(LlmEvent.Delta("番茄炒蛋。"), awaitItem())
            assertEquals(LlmEvent.Done(null), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a stream cut off before it finished is a network error`() = runTest {
        server.enqueue(sse("stream_truncated.txt").build())
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
            sse("stream_normal.txt")
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
    fun `a malformed chunk is reported`() = runTest {
        server.enqueue(sse("stream_malformed.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.MalformedResponse), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `an error object mid-stream ends the reply with its kind`() = runTest {
        server.enqueue(sse("stream_error.txt").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.RateLimited), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `mid-stream error identifiers and numeric codes map to error kinds`() = runTest {
        val cases = listOf(
            """{"type":"invalid_request_error","code":"invalid_api_key"}""" to LlmErrorKind.InvalidKey,
            """{"type":"invalid_request_error","code":"context_length_exceeded"}""" to LlmErrorKind.Server(400),
            """{"type":"server_error","code":null}""" to LlmErrorKind.Server(500),
            """{"code":503}""" to LlmErrorKind.Server(503),
            """{"message":"no identifiers"}""" to LlmErrorKind.MalformedResponse,
        )
        cases.forEach { (error, _) ->
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "text/event-stream")
                    .body("data: {\"error\":$error}\n\n")
                    .build(),
            )
        }
        cases.forEach { (error, kind) ->
            client().stream(request).test {
                assertEquals(error, LlmEvent.Error(kind), awaitItem())
                awaitComplete()
            }
        }
    }

    @Test
    fun `401 and 403 mean an invalid key`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body(fixture("error_401.json")).build())
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.InvalidKey), awaitItem())
            awaitComplete()
        }
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.InvalidKey), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `429 means rate limited`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).body(fixture("error_429.json")).build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.RateLimited), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `other statuses carry their code`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("upstream overloaded").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.Server(503)), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a successful response that is not an event stream is malformed`() = runTest {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html").body("<html/>").build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.MalformedResponse), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `no response in time is a timeout`() = runTest {
        server.enqueue(sse("stream_normal.txt").headersDelay(2, TimeUnit.SECONDS).build())
        client().stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.Timeout), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a stalled stream times out`() = runTest {
        server.enqueue(
            // The first event arrives, then nothing for longer than the read timeout.
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .removeHeader("Content-Length") // the body runs until the socket closes
                .socketHandler(
                    object : SocketHandler {
                        override fun handle(socket: Socket) {
                            val firstEvent = fixture("stream_truncated.txt").lines().take(2).joinToString("\n")
                            socket.sink.buffer().writeUtf8("$firstEvent\n\n").flush()
                            Thread.sleep(STALL_MILLIS)
                        }
                    },
                )
                .build(),
        )
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            assertEquals(LlmEvent.Error(LlmErrorKind.Timeout), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a slow collector gets every delta in order and the done event`() {
        val words = (1..SLOW_STREAM_EVENTS).map { "w$it " }
        val body = buildString {
            words.forEach { append("data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n") }
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(body).build())

        // Real time, so the reader thread runs far ahead of a collector that is slow on every item.
        val events = runBlocking { client().stream(request).onEach { delay(2) }.toList() }

        assertEquals(words.map { LlmEvent.Delta(it) } + LlmEvent.Done(null), events)
    }

    @Test
    fun `an unreachable host is a network error`() = runTest {
        val url = server.url("/v1")
        server.close()
        OpenAiCompatibleClient(url, MODEL, PLACEHOLDER_KEY, httpClient, json).stream(request).test {
            assertEquals(LlmEvent.Error(LlmErrorKind.Network), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `cancelling the collector cancels the http call`() = runTest {
        server.enqueue(sse("stream_normal.txt").throttleBody(64, 100, TimeUnit.MILLISECONDS).build())
        client().stream(request).test {
            assertEquals(LlmEvent.Delta("Try"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("OkHttp call was not cancelled", callCancelled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `lists model ids sorted and deduplicated`() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture("models.json")).build())

        assertEquals(ModelListResult.Success(listOf("gpt-test", "gpt-test-mini")), client().listModels())
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.url.encodedPath)
        assertEquals("Bearer $PLACEHOLDER_KEY", recorded.headers["Authorization"])
    }

    @Test
    fun `listing models maps errors`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body(fixture("error_401.json")).build())
        server.enqueue(MockResponse.Builder().body("not json").build())
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).build())

        assertEquals(ModelListResult.Failure(LlmErrorKind.InvalidKey), client().listModels())
        assertEquals(ModelListResult.Failure(LlmErrorKind.MalformedResponse), client().listModels())
        assertEquals(ModelListResult.Failure(LlmErrorKind.Timeout), client().listModels())
    }

    private companion object {
        /** Well past the 64 events a callbackFlow buffers by default. */
        const val SLOW_STREAM_EVENTS = 300
        const val MODEL = "gpt-test"
        const val STALL_MILLIS = 1_500L // longer than the 500 ms read timeout
    }
}
