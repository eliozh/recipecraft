package com.elio.recipecraft.data.llm

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    /** A [Random] whose next long in a range is always its lowest or highest value. */
    private class EdgeRandom(private val high: Boolean) : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(from: Long, until: Long) = if (high) until - 1 else from
    }

    @Test
    fun `backoff doubles from the base delay and stays within its jitter bounds`() {
        val low = RetryPolicy(maxAttempts = 5, baseDelayMillis = 2_000, maxDelayMillis = 30_000, random = EdgeRandom(high = false))
        val high = RetryPolicy(maxAttempts = 5, baseDelayMillis = 2_000, maxDelayMillis = 30_000, random = EdgeRandom(high = true))
        // Equal jitter: between half the backoff and the whole of it.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { low.delayMillis(it, null) })
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), (1..4).map { high.delayMillis(it, null) })
    }

    @Test
    fun `random jitter stays within half and all of the backoff`() {
        val policy = RetryPolicy(maxAttempts = 3, random = Random(42))
        repeat(200) {
            val first = policy.delayMillis(1, null)!!
            val second = policy.delayMillis(2, null)!!
            assertTrue(first in 1_000L..2_000L)
            assertTrue(second in 2_000L..4_000L)
        }
    }

    @Test
    fun `backoff is capped at the maximum delay`() {
        val policy = RetryPolicy(maxAttempts = 30, baseDelayMillis = 2_000, maxDelayMillis = 10_000, random = EdgeRandom(high = true))
        assertEquals(10_000L, policy.delayMillis(10, null))
        assertEquals(10_000L, policy.delayMillis(29, null))
    }

    @Test
    fun `stops after the last attempt`() {
        val policy = RetryPolicy()
        assertEquals(3, policy.maxAttempts)
        assertTrue(policy.delayMillis(2, null) != null)
        assertNull(policy.delayMillis(3, null))
        assertNull(RetryPolicy(maxAttempts = 1).delayMillis(1, null))
    }

    @Test
    fun `retry-after is honoured, unless it asks for longer than the maximum`() {
        val policy = RetryPolicy(random = EdgeRandom(high = true))
        assertEquals(7_000L, policy.delayMillis(1, 7_000))
        assertEquals(0L, policy.delayMillis(1, 0))
        assertNull(policy.delayMillis(1, 31_000))
        assertNull(policy.delayMillis(3, 1_000))
    }

    @Test
    fun `retry-after is read as seconds or as an http date, never from a success`() {
        val clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC)
        fun response(code: Int, retryAfter: String?) = Response.Builder()
            .request(Request.Builder().url("https://llm.example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build()

        assertEquals(5_000L, retryAfterMillis(response(429, "5"), clock))
        assertEquals(20_000L, retryAfterMillis(response(429, "Wed, 28 Oct 2026 12:00:20 GMT"), clock))
        assertEquals(0L, retryAfterMillis(response(429, "Wed, 28 Oct 2026 11:00:00 GMT"), clock))
        assertNull(retryAfterMillis(response(429, null), clock))
        assertNull(retryAfterMillis(response(429, "soon"), clock))
        assertNull(retryAfterMillis(response(429, "-3"), clock))
        assertNull(retryAfterMillis(response(200, "5"), clock))
        assertNull(retryAfterMillis(null, clock))
    }

    /** Answers each stream call with the next scripted flow. */
    private class ScriptedClient(vararg replies: Flow<LlmEvent>) : LlmClient {
        private val replies = ArrayDeque(replies.toList())
        var calls = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            calls++
            return replies.removeFirst()
        }

        override suspend fun listModels() = ModelListResult.Success(listOf("m"))
    }

    private val request = LlmRequest(listOf(LlmMessage(LlmMessage.Role.USER, "hi")))
    private val limited = flowOf(LlmEvent.Error(LlmErrorKind.RateLimited))
    private val fixed = RetryPolicy(random = EdgeRandom(high = true))

    @Test
    fun `a rate-limited request is sent again after the backoff and its reply passes through`() = runTest {
        val delegate = ScriptedClient(limited, flowOf(LlmEvent.Delta("ok"), LlmEvent.Done(null)))
        val events = RetryingLlmClient(delegate, fixed).stream(request).toList()

        assertEquals(listOf(LlmEvent.Retrying(1, 2_000), LlmEvent.Delta("ok"), LlmEvent.Done(null)), events)
        assertEquals(2, delegate.calls)
        assertEquals("the wait runs on the test clock", 2_000L, currentTime)
    }

    @Test
    fun `the error ends the flow once the attempts run out`() = runTest {
        val delegate = ScriptedClient(limited, limited, flowOf(LlmEvent.Error(LlmErrorKind.RateLimited, retryAfterMillis = 1_000)))
        val events = RetryingLlmClient(delegate, fixed).stream(request).toList()

        assertEquals(
            listOf(
                LlmEvent.Retrying(1, 2_000),
                LlmEvent.Retrying(2, 4_000),
                LlmEvent.Error(LlmErrorKind.RateLimited, retryAfterMillis = 1_000),
            ),
            events,
        )
        assertEquals(3, delegate.calls)
    }

    @Test
    fun `retry-after sets the wait`() = runTest {
        val waits = mutableListOf<Long>()
        val delegate = ScriptedClient(flowOf(LlmEvent.Error(LlmErrorKind.RateLimited, retryAfterMillis = 9_000)), flowOf(LlmEvent.Done(null)))
        val events = RetryingLlmClient(delegate, fixed, sleep = { waits += it }).stream(request).toList()

        assertEquals(listOf(LlmEvent.Retrying(1, 9_000), LlmEvent.Done(null)), events)
        assertEquals(listOf(9_000L), waits)
        assertEquals(9, LlmEvent.Retrying(1, 8_001).delaySeconds)
    }

    @Test
    fun `a rate limit after the first delta is not retried, so no text is sent twice`() = runTest {
        val delegate = ScriptedClient(flowOf(LlmEvent.Delta("par"), LlmEvent.Error(LlmErrorKind.RateLimited)))
        val events = RetryingLlmClient(delegate, fixed).stream(request).toList()

        assertEquals(listOf(LlmEvent.Delta("par"), LlmEvent.Error(LlmErrorKind.RateLimited)), events)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `other errors are never retried`() = runTest {
        val delegate = ScriptedClient(flowOf(LlmEvent.Error(LlmErrorKind.Network)))
        assertEquals(listOf(LlmEvent.Error(LlmErrorKind.Network)), RetryingLlmClient(delegate, fixed).stream(request).toList())
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `cancelling during the wait sends nothing more`() = runTest {
        val delegate = ScriptedClient(limited, flow { error("must not be called") })
        val events = mutableListOf<LlmEvent>()
        val job = launch { RetryingLlmClient(delegate, fixed).stream(request).collect { events += it } }
        runCurrent()
        assertEquals(listOf(LlmEvent.Retrying(1, 2_000)), events)

        advanceTimeBy(1_000)
        job.cancel()
        advanceTimeBy(5_000)
        assertEquals(1, delegate.calls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a collector that stops at the first event stops the retries`() = runTest {
        val delegate = ScriptedClient(limited, flow { error("must not be called") })
        assertEquals(listOf(LlmEvent.Retrying(1, 2_000)), RetryingLlmClient(delegate, fixed).stream(request).take(1).toList())
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `listing models goes straight to the provider`() = runTest {
        assertEquals(ModelListResult.Success(listOf("m")), RetryingLlmClient(ScriptedClient(), fixed).listModels())
    }

    @Test
    fun `collectReply reports the wait, then clears it once the reply starts`() = runTest {
        val delegate = ScriptedClient(limited, flowOf(LlmEvent.Delta("a"), LlmEvent.Delta("b"), LlmEvent.Done(null)))
        val waits = mutableListOf<Int?>()
        val text = StringBuilder()
        val error = RetryingLlmClient(delegate, fixed).collectReply(request, onRetryWait = { waits += it }) { text.append(it) }

        assertNull(error)
        assertEquals("ab", text.toString())
        assertEquals(listOf(2, null), waits)
    }

    @Test
    fun `collectReply returns the error that ended the reply`() = runTest {
        val delegate = ScriptedClient(flowOf(LlmEvent.Error(LlmErrorKind.Timeout)))
        assertEquals(LlmErrorKind.Timeout, delegate.collectReply(request) {})
    }
}
