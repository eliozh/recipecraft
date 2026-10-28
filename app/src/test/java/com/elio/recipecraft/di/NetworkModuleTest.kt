package com.elio.recipecraft.di

import com.elio.recipecraft.data.llm.AnthropicClient
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.llm.OpenAiCompatibleClient
import com.elio.recipecraft.testing.PLACEHOLDER_KEY
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkModuleTest {

    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val httpClient = NetworkModule.provideOkHttpClient()
    private val json = NetworkModule.provideJson()
    private val request = LlmRequest(listOf(LlmMessage(LlmMessage.Role.USER, "Breakfast idea?")))

    @Before
    fun setUp() {
        server.start()
        elsewhere.start()
    }

    @After
    fun tearDown() {
        server.close()
        elsewhere.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun redirect(code: Int) = MockResponse.Builder()
        .code(code)
        .setHeader("Location", elsewhere.url("/v1/messages"))
        .build()

    @Test
    fun `a redirected chat request is an error and the body and key go nowhere else`() = runBlocking {
        server.enqueue(redirect(307))
        server.enqueue(redirect(308))
        val anthropic = AnthropicClient(server.url("/"), "claude-test", PLACEHOLDER_KEY, httpClient, json)
        val openAi = OpenAiCompatibleClient(server.url("/v1"), "gpt-test", PLACEHOLDER_KEY, httpClient, json)

        assertEquals(listOf(LlmEvent.Error(LlmErrorKind.Server(307))), anthropic.stream(request).toList())
        assertEquals(listOf(LlmEvent.Error(LlmErrorKind.Server(308))), openAi.stream(request).toList())
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun `a redirected models request is an error`() = runBlocking {
        server.enqueue(redirect(302))
        val anthropic = AnthropicClient(server.url("/"), "claude-test", PLACEHOLDER_KEY, httpClient, json)

        assertEquals(ModelListResult.Failure(LlmErrorKind.Server(302)), anthropic.listModels())
        assertEquals(0, elsewhere.requestCount)
    }
}
