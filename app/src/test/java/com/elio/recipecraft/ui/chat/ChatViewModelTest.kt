package com.elio.recipecraft.ui.chat

import app.cash.turbine.test
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val client = FakeLlmClient()
    private val factory = FakeLlmClientFactory(LlmClientResult.Ready(client))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ChatViewModel.send(text: String) {
        onInputChanged(text)
        onSend()
    }

    private val ChatUiState.texts get() = messages.map { it.role to it.text }

    @Test
    fun `deltas are appended to the reply until done`() = runTest {
        val vm = ChatViewModel(factory)
        vm.uiState.test {
            assertEquals(ChatUiState(), awaitItem())
            vm.onInputChanged("Breakfast?")
            assertTrue(awaitItem().canSend)

            vm.onSend()
            var state = expectMostRecentItem()
            assertTrue(state.sending)
            assertEquals("", state.input)
            assertEquals(listOf(Role.USER to "Breakfast?", Role.ASSISTANT to ""), state.texts)

            client.events.send(LlmEvent.Delta("Try"))
            assertEquals("Try", awaitItem().messages.last().text)
            client.events.send(LlmEvent.Delta(" oats."))
            assertEquals("Try oats.", awaitItem().messages.last().text)

            client.events.send(LlmEvent.Done(null))
            state = expectMostRecentItem()
            assertFalse(state.sending)
            assertNull(state.streamingMessageId)
            assertNull(state.error)
            assertEquals(listOf(Role.USER to "Breakfast?", Role.ASSISTANT to "Try oats."), state.texts)
        }
    }

    @Test
    fun `the whole conversation is sent with each turn`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hello"))
        client.events.send(LlmEvent.Done(null))
        vm.send("  Eggs?  ")

        assertEquals(
            listOf(
                LlmMessage(Role.USER, "Hi"),
                LlmMessage(Role.ASSISTANT, "Hello"),
                LlmMessage(Role.USER, "Eggs?"),
            ),
            client.requests.last().messages,
        )
    }

    @Test
    fun `an error shows its kind and drops the empty reply`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        client.events.send(LlmEvent.Error(LlmErrorKind.InvalidKey))

        val state = vm.uiState.value
        assertEquals(LlmErrorKind.InvalidKey, state.error)
        assertFalse(state.sending)
        assertEquals(listOf(Role.USER to "Hi"), state.texts)
    }

    @Test
    fun `an error after some text keeps the partial reply`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hel"))
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))

        assertEquals(LlmErrorKind.Network, vm.uiState.value.error)
        assertEquals(listOf(Role.USER to "Hi", Role.ASSISTANT to "Hel"), vm.uiState.value.texts)
    }

    @Test
    fun `no model selected asks to pick one and keeps the input`() = runTest {
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingModel)
        val vm = ChatViewModel(factory)
        vm.send("Hi")

        val state = vm.uiState.value
        assertEquals(LlmErrorKind.MissingModel, state.error)
        assertEquals("Hi", state.input)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.sending)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `sending again clears the previous error`() = runTest {
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        factory.result = LlmClientResult.Ready(client)
        vm.onSend()

        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.sending)
    }

    @Test
    fun `stop cancels the stream and keeps the partial reply`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hel"))

        vm.onStop()

        assertTrue(client.cancelled)
        val state = vm.uiState.value
        assertFalse(state.sending)
        assertNull(state.streamingMessageId)
        assertEquals(listOf(Role.USER to "Hi", Role.ASSISTANT to "Hel"), state.texts)

        client.events.send(LlmEvent.Delta("lo"))
        assertEquals("Hel", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `stop before any text drops the empty reply`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("Hi")
        vm.onStop()
        assertEquals(listOf(Role.USER to "Hi"), vm.uiState.value.texts)
    }

    @Test
    fun `blank input or a reply in progress does not send`() = runTest {
        val vm = ChatViewModel(factory)
        vm.send("   ")
        assertTrue(client.requests.isEmpty())

        vm.send("Hi")
        vm.send("Again")
        assertEquals(1, client.requests.size)
        assertFalse(vm.uiState.value.canSend)
    }
}
