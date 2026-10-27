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
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.domain.GenerationShortfall
import com.elio.recipecraft.domain.IncompleteSet
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.daySets
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
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
    private val recipeDao = FakeRecipeDao()
    private val recipes = RecipeRepository(recipeDao, nutrition, UnconfinedTestDispatcher())
    private var ids = 0

    private fun newViewModel() = ChatViewModel(
        clientFactory = factory,
        promptBuilder = PromptBuilder(
            assets = { path -> File("src/main/assets/$path").readText() },
            languageSettings = language,
            ioDispatcher = UnconfinedTestDispatcher(),
        ),
        parser = RecipeParser(),
        assembler = RecipeAssembler(Clock.fixed(now, ZoneOffset.UTC)) { "id-${++ids}" },
        nutrition = nutrition,
        recipes = recipes,
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
        assertEquals("id-1", recipe.id)
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

    private fun ChatViewModel.generate(scope: GenerationScope, count: Int, preferences: String = "") {
        onScopeChanged(scope)
        onCountChanged(count)
        onPreferencesChanged(preferences)
        onGenerate()
    }

    private val fullDay = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)

    @Test
    fun `the composer starts at one full-day set and keeps the count between 1 and 10`() = runTest {
        val vm = newViewModel()
        assertEquals(ComposerState(), vm.uiState.value.composer)
        assertEquals(GenerationRequest(GenerationScope.FULL_DAY_SET, 1), vm.uiState.value.composer.toRequest())

        vm.onCountChanged(0)
        assertEquals(1, vm.uiState.value.composer.count)
        vm.onCountChanged(11)
        assertEquals(10, vm.uiState.value.composer.count)
        vm.onPreferencesChanged("  vegan ")
        vm.onScopeChanged(GenerationScope.DESSERT)
        assertEquals(GenerationRequest(GenerationScope.DESSERT, 10, "vegan"), vm.uiState.value.composer.toRequest())
        vm.onComposerExpandedChanged(false)
        assertFalse(vm.uiState.value.composer.expanded)
    }

    @Test
    fun `full-day set x2 streams into two grouped sets of breakfast, lunch and dinner`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 2)

        val sent = client.requests.single().messages.single().content
        assertTrue(sent.contains("- Scope: full_day_set\n- Count: 2\n"))
        var state = vm.uiState.value
        val user = state.messages.first()
        assertEquals(GenerationRequest(GenerationScope.FULL_DAY_SET, 2), user.generation)
        assertEquals(sent, user.text)
        assertEquals("", user.shownText)
        assertFalse(state.composer.expanded)
        assertTrue(state.sending)

        reply(fixture("en_full_day_x2.txt"))
        state = vm.uiState.value
        val reply = state.messages.last()
        assertEquals("Here are two balanced days of eating.", reply.shownText)
        val sets = reply.recipes.daySets()
        assertEquals(2, sets.size)
        sets.forEach { set -> assertEquals(fullDay, set.map { it.mealType }) }
        assertEquals(listOf("Greek yogurt parfait", "Peanut butter toast"), sets.map { it.first().name })
        assertTrue(sets[0].first().setId != sets[1].first().setId)
        assertNull(reply.shortfall)
        assertFalse(state.sending)
    }

    @Test
    fun `a chinese full-day reply is grouped the same way`() = runTest {
        language.resolved = Language.ZH
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 2)
        assertTrue(client.requests.single().messages.single().content.contains("- 范围：full_day_set"))
        reply(fixture("zh_full_day_x2.txt"))

        val reply = vm.uiState.value.messages.last()
        val sets = reply.recipes.daySets()
        assertEquals(listOf("小米粥配鸡蛋", "豆浆油条"), sets.map { it.first().name })
        sets.forEach { set -> assertEquals(fullDay, set.map { it.mealType }) }
        assertTrue(reply.recipes.all { it.language == Language.ZH })
        assertNull(reply.shortfall)
    }

    @Test
    fun `dinner x3 yields three distinct dinners outside any set`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.DINNER, 3, "quick")
        assertTrue(client.requests.single().messages.single().content.contains("<<<quick>>>"))
        reply(fixture("en_dinner_x3.txt"))

        val recipes = vm.uiState.value.messages.last().recipes
        assertEquals(3, recipes.size)
        assertEquals(3, recipes.map { it.name }.toSet().size)
        assertTrue(recipes.all { it.mealType == MealType.DINNER && it.setId == null })
        assertNull(vm.uiState.value.messages.last().shortfall)
    }

    @Test
    fun `fewer recipes than asked are kept with a warning and no retry`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.DINNER, 4)
        reply(fixture("en_dinner_x3.txt"))

        val reply = vm.uiState.value.messages.last()
        assertEquals(3, reply.recipes.size)
        assertEquals(GenerationShortfall(asked = 4, got = 3, sets = false), reply.shortfall)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `an incomplete set is reported by day`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 2)
        reply(fixture("en_full_day_short.txt"))

        val reply = vm.uiState.value.messages.last()
        assertEquals(4, reply.recipes.size)
        assertEquals(
            GenerationShortfall(
                asked = 2,
                got = 2,
                sets = true,
                incomplete = listOf(IncompleteSet(2, listOf(MealType.LUNCH, MealType.DINNER))),
            ),
            reply.shortfall,
        )
    }

    @Test
    fun `fewer sets than asked is short`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 3)
        reply(fixture("en_full_day_x2.txt"))
        assertEquals(GenerationShortfall(asked = 3, got = 2, sets = true), vm.uiState.value.messages.last().shortfall)
    }

    @Test
    fun `free-form recipes stay outside sets and are never checked`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_breakfast.txt"))
        val reply = vm.uiState.value.messages.last()
        assertNull(reply.recipes.single().setId)
        assertNull(reply.shortfall)
        assertNull(vm.uiState.value.messages.first().generation)
    }

    @Test
    fun `a repaired composer reply is still grouped and checked`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 2)
        reply(fixture("en_malformed.txt"))
        reply(fixture("en_full_day_x2.txt"))

        assertEquals(2, client.requests.size)
        val reply = vm.uiState.value.messages.last()
        assertEquals(2, reply.recipes.daySets().size)
        assertNull(reply.shortfall)
    }

    @Test
    fun `retrying a composer reply resends the composer request`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.LUNCH, 2)
        client.events.send(LlmEvent.Delta("```json\n{\"recipes\""))
        vm.onStop()

        vm.onRetry(vm.uiState.value.messages.last().id)
        assertEquals(2, client.requests.size)
        assertEquals(client.requests[0].messages, client.requests[1].messages)
        reply(fixture("en_dinner_x3.txt"))
        assertEquals(GenerationRequest(GenerationScope.LUNCH, 2), vm.uiState.value.messages.first().generation)
        assertNull(vm.uiState.value.messages.last().shortfall)
    }

    @Test
    fun `generate is ignored while a reply is in progress, and a missing key keeps the composer open`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        vm.onGenerate()
        assertEquals(1, client.requests.size)
        client.events.send(LlmEvent.Done(null))

        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        vm.onGenerate()
        assertEquals(LlmErrorKind.MissingKey, vm.uiState.value.error)
        assertTrue(vm.uiState.value.composer.expanded)
    }

    @Test
    fun `a later free-form turn sends the composer turn as history`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.SNACK, 1)
        reply(fixture("en_dinner_x3.txt"))
        vm.send("Make the first one spicier")

        val history = client.requests.last().messages
        assertEquals(3, history.size)
        assertEquals(client.requests.first().messages.single(), history.first())
    }

    @Test
    fun `save puts a recipe in the library once, and its card shows saved`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_breakfast.txt"))
        val recipe = vm.uiState.value.messages.last().recipes.single()
        assertTrue(vm.uiState.value.savedIds.isEmpty())

        vm.onSave(recipe)
        vm.onSave(recipe)

        assertEquals(listOf(recipe.id), recipeDao.recipes.map { it.id })
        assertEquals(setOf(recipe.id), vm.uiState.value.savedIds)
        assertFalse(vm.uiState.value.saveFailed)
    }

    @Test
    fun `save all saves the recipes of one set that are not saved yet`() = runTest {
        val vm = newViewModel()
        vm.generate(GenerationScope.FULL_DAY_SET, 2)
        reply(fixture("en_full_day_x2.txt"))
        val (day1, day2) = vm.uiState.value.messages.last().recipes.daySets()

        vm.onSave(day1[1])
        vm.onSaveAll(day1)

        assertEquals(day1.map { it.id }.toSet(), recipeDao.recipes.map { it.id }.toSet())
        assertEquals(3, recipeDao.recipes.size)
        assertEquals(day1.map { it.id }.toSet(), vm.uiState.value.savedIds)
        assertTrue(day2.none { it.id in vm.uiState.value.savedIds })
    }

    @Test
    fun `a failed save is reported until dismissed`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_breakfast.txt"))
        val recipe = vm.uiState.value.messages.last().recipes.single()

        recipeDao.failWrites = true
        vm.onSave(recipe)
        assertTrue(vm.uiState.value.saveFailed)
        assertTrue(vm.uiState.value.savedIds.isEmpty())

        vm.onSaveFailedDismissed()
        assertFalse(vm.uiState.value.saveFailed)
    }
}
