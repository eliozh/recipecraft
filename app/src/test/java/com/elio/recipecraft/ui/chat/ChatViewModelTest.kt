package com.elio.recipecraft.ui.chat

import app.cash.turbine.test
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.oatmealUsdaLookup
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    private val language = FakeLanguageSettings()
    private val now = Instant.parse("2026-10-27T08:00:00Z")
    private val nutrition = FakeNutritionLookups()

    private fun newViewModel() = ChatViewModel(
        clientFactory = factory,
        promptBuilder = PromptBuilder(
            assets = { path -> File("src/main/assets/$path").readText() },
            languageSettings = language,
            ioDispatcher = UnconfinedTestDispatcher(),
        ),
        parser = RecipeParser(),
        assembler = RecipeAssembler(Clock.fixed(now, ZoneOffset.UTC)) { "recipe-id" },
        nutrition = nutrition,
    )

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    /** Streams [text] in small chunks, as a provider would, then ends the reply. */
    private suspend fun reply(text: String) {
        text.chunked(40).forEach { client.events.send(LlmEvent.Delta(it)) }
        client.events.send(LlmEvent.Done(null))
    }

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
        val vm = newViewModel()
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
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hello"))
        client.events.send(LlmEvent.Done(null))
        vm.send("  Eggs?  ")

        val messages = client.requests.last().messages
        assertEquals(listOf(LlmMessage(Role.USER, "Hi"), LlmMessage(Role.ASSISTANT, "Hello")), messages.take(2))
        // The new turn goes through generate.md, which starts with the user's text.
        assertTrue(messages[2].content.startsWith("Eggs?\n"))
        assertEquals(3, messages.size)
    }

    @Test
    fun `an error shows its kind and drops the empty reply`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Error(LlmErrorKind.InvalidKey))

        val state = vm.uiState.value
        assertEquals(LlmErrorKind.InvalidKey, state.error)
        assertFalse(state.sending)
        assertEquals(listOf(Role.USER to "Hi"), state.texts)
    }

    @Test
    fun `an error after some text keeps the partial reply`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hel"))
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))

        assertEquals(LlmErrorKind.Network, vm.uiState.value.error)
        assertEquals(listOf(Role.USER to "Hi", Role.ASSISTANT to "Hel"), vm.uiState.value.texts)
    }

    @Test
    fun `no model selected asks to pick one and keeps the input`() = runTest {
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingModel)
        val vm = newViewModel()
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
        val vm = newViewModel()
        vm.send("Hi")
        factory.result = LlmClientResult.Ready(client)
        vm.onSend()

        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.sending)
    }

    @Test
    fun `stop cancels the stream and keeps the partial reply`() = runTest {
        val vm = newViewModel()
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
        val vm = newViewModel()
        vm.send("Hi")
        vm.onStop()
        assertEquals(listOf(Role.USER to "Hi"), vm.uiState.value.texts)
    }

    @Test
    fun `blank input or a reply in progress does not send`() = runTest {
        val vm = newViewModel()
        vm.send("   ")
        assertTrue(client.requests.isEmpty())

        vm.send("Hi")
        vm.send("Again")
        assertEquals(1, client.requests.size)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun `a recipe reply streams its prose, then becomes recipes`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        val text = fixture("en_breakfast.txt")
        val prose = "Here is a warm, filling breakfast that takes about ten minutes."

        client.events.send(LlmEvent.Delta(text.substringBefore("```") + "```js"))
        var reply = vm.uiState.value.messages.last()
        assertEquals(RecipeStatus.BUILDING, reply.recipeStatus)
        assertEquals(prose, reply.shownText)

        client.events.send(LlmEvent.Delta(text.substringAfter("```js")))
        client.events.send(LlmEvent.Done(null))
        reply = vm.uiState.value.messages.last()
        assertEquals(RecipeStatus.NONE, reply.recipeStatus)
        assertEquals(prose, reply.shownText)
        assertEquals(text, reply.text)
        val recipe = reply.recipes.single()
        assertEquals("Banana oatmeal with milk", recipe.name)
        assertEquals("recipe-id", recipe.id)
        assertEquals(now.toEpochMilli(), recipe.createdAt)
        assertEquals(Language.EN, recipe.language)
        assertEquals(CalorieSource.AI_ESTIMATE, recipe.calories.source)
        assertEquals(560, recipe.calories.total)
        assertTrue(recipe.ingredients.all { it.nameEn.isNotBlank() })
        assertEquals(1, client.requests.size)
        assertFalse(vm.uiState.value.sending)
    }

    @Test
    fun `recipes take a database total when every ingredient matches a table`() = runTest {
        nutrition.lookups = listOf(oatmealUsdaLookup())
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_breakfast.txt"))

        val calories = vm.uiState.value.messages.last().recipes.single().calories
        assertEquals(CalorieSource.DATABASE, calories.source)
        assertEquals(653, calories.total)
        assertEquals(560, calories.aiTotal)
    }

    @Test
    fun `a chinese reply is stamped with the language of the prompt`() = runTest {
        language.resolved = Language.ZH
        val vm = newViewModel()
        vm.send("来一份早餐")
        assertTrue(client.requests.single().system!!.contains("简体中文"))
        reply(fixture("zh_breakfast.txt"))

        val recipe = vm.uiState.value.messages.last().recipes.single()
        assertEquals("葱花鸡蛋饼", recipe.name)
        assertEquals(Language.ZH, recipe.language)
        assertEquals("egg", recipe.ingredients[1].nameEn)
    }

    @Test
    fun `free text stays a text bubble without a repair`() = runTest {
        val vm = newViewModel()
        vm.send("What replaces buttermilk?")
        val text = fixture("en_free_text.txt")
        reply(text)

        val message = vm.uiState.value.messages.last()
        assertEquals(text, message.shownText)
        assertEquals(RecipeStatus.NONE, message.recipeStatus)
        assertTrue(message.recipes.isEmpty())
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `malformed json sends exactly one repair request, then shows its recipes`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        val bad = fixture("en_malformed.txt")
        reply(bad)

        assertEquals(2, client.requests.size)
        assertEquals(RecipeStatus.REPAIRING, vm.uiState.value.messages.last().recipeStatus)
        assertTrue(vm.uiState.value.sending)
        val repair = client.requests.last()
        assertTrue(repair.messages.single().content.contains(bad.trim()))
        assertTrue(repair.messages.single().content.contains("the JSON is not valid"))

        reply(fixture("en_repaired.txt"))
        val message = vm.uiState.value.messages.last()
        assertEquals("Peanut butter toast", message.recipes.single().name)
        assertEquals("Here is a quick breakfast for you.", message.shownText)
        assertEquals(RecipeStatus.NONE, message.recipeStatus)
        assertEquals(2, client.requests.size)
        assertFalse(vm.uiState.value.sending)
    }

    @Test
    fun `a failed repair shows the raw text, and retry asks again`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        val bad = fixture("en_malformed.txt")
        reply(bad)
        reply("Sorry, here it is again: {\"recipes\": [")

        assertEquals(2, client.requests.size)
        val failed = vm.uiState.value.messages.last()
        assertEquals(RecipeStatus.FAILED, failed.recipeStatus)
        assertEquals(bad, failed.shownText)
        assertTrue(failed.recipes.isEmpty())
        assertFalse(vm.uiState.value.sending)

        vm.onRetry(failed.id)
        assertEquals(3, client.requests.size)
        assertTrue(client.requests.last().messages.single().content.startsWith("Give me a breakfast recipe\n"))
        reply(fixture("en_breakfast.txt"))

        val texts = vm.uiState.value.messages
        assertEquals(2, texts.size)
        assertEquals("Banana oatmeal with milk", texts.last().recipes.single().name)
    }

    @Test
    fun `a network error during the repair fails the reply and shows the error`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_malformed.txt"))
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))

        assertEquals(RecipeStatus.FAILED, vm.uiState.value.messages.last().recipeStatus)
        assertEquals(LlmErrorKind.Network, vm.uiState.value.error)
    }

    @Test
    fun `stopping while recipes are building leaves the raw text to retry`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        client.events.send(LlmEvent.Delta("Here:\n```json\n{\"recipes\""))
        vm.onStop()

        val message = vm.uiState.value.messages.last()
        assertEquals(RecipeStatus.FAILED, message.recipeStatus)
        assertEquals("Here:\n```json\n{\"recipes\"", message.shownText)
    }

    @Test
    fun `retry is ignored while a reply is in progress or for an unknown message`() = runTest {
        val vm = newViewModel()
        vm.onRetry(42)
        assertTrue(client.requests.isEmpty())

        vm.send("Hi")
        vm.onRetry(vm.uiState.value.messages.last().id)
        assertEquals(1, client.requests.size)
    }
}
