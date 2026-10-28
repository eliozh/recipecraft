package com.elio.recipecraft.ui.chat

import app.cash.turbine.test
import com.elio.recipecraft.data.chat.ChatMessage
import com.elio.recipecraft.data.chat.ChatRepository
import com.elio.recipecraft.data.chat.RecipeStatus
import com.elio.recipecraft.data.chat.ReplyState
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.RecipeModifier
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
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.recipe.SaveState
import com.elio.recipecraft.testing.FakeChatDao
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.MutableClock
import com.elio.recipecraft.testing.oatmealUsdaLookup
import java.io.File
import java.time.Instant
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

    private val clock = MutableClock(now)
    private val chatDao = FakeChatDao()
    private val chats = ChatRepository(chatDao, clock, UnconfinedTestDispatcher())
    private val promptBuilder = PromptBuilder(
        assets = { path -> File("src/main/assets/$path").readText() },
        languageSettings = language,
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val assembler = RecipeAssembler(clock) { "id-${++ids}" }

    private fun newViewModel() = ChatViewModel(
        clientFactory = factory,
        promptBuilder = promptBuilder,
        parser = RecipeParser(),
        assembler = assembler,
        nutrition = nutrition,
        recipes = recipes,
        recipeModifier = RecipeModifier(factory, promptBuilder, RecipeParser(), assembler, nutrition, clock),
        chats = chats,
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
    fun `a rate-limit wait is shown until the reply starts, and cleared when it ends`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Retrying(1, 1_500))
        assertEquals(2, vm.uiState.value.retryingInSeconds)
        assertTrue(vm.uiState.value.sending)

        client.events.send(LlmEvent.Delta("Hello"))
        assertNull(vm.uiState.value.retryingInSeconds)
        client.events.send(LlmEvent.Retrying(2, 3_000))
        vm.onStop()
        assertNull(vm.uiState.value.retryingInSeconds)
    }

    @Test
    fun `a modify that waits out a rate limit shows the wait`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val recipe = message.recipes.first()
        vm.modify(message, recipe, "Make it vegetarian")
        client.events.send(LlmEvent.Retrying(1, 4_000))
        assertEquals(4, vm.uiState.value.modify?.retryingInSeconds)
        client.events.send(LlmEvent.Error(LlmErrorKind.RateLimited))
        assertNull(vm.uiState.value.modify?.retryingInSeconds)
        assertFalse(vm.uiState.value.modify!!.running)
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

    /** A chat with two full-day sets from en_full_day_x2.txt; returns the model's reply message. */
    private suspend fun twoDays(vm: ChatViewModel): ChatMessage {
        vm.generate(GenerationScope.FULL_DAY_SET, 2)
        reply(fixture("en_full_day_x2.txt"))
        return vm.uiState.value.messages.last()
    }

    private fun ChatViewModel.modify(message: ChatMessage, recipe: Recipe, instruction: String) {
        onModify(message.id, recipe.id)
        onModifyInstructionChanged(instruction)
        onModifySend()
    }

    private fun ChatViewModel.recipesOf(message: ChatMessage) =
        uiState.value.messages.first { it.id == message.id }.recipes

    @Test
    fun `changing the lunch of a set changes only the lunch`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val before = message.recipes
        val lunch = before.first { it.mealType == MealType.LUNCH }
        clock.instant = now.plusSeconds(60)

        vm.onModify(message.id, lunch.id)
        assertEquals(ModifyState(message.id, lunch.id), vm.uiState.value.modify)
        vm.onModifyInstructionChanged("Make it vegetarian")
        vm.onModifySend()
        assertTrue(vm.uiState.value.isModifying(lunch.id))
        reply(fixture("modify_en_lunch_vegetarian.txt"))

        val after = vm.recipesOf(message)
        val changed = after.first { it.id == lunch.id }
        assertEquals("Chickpea salad wrap", changed.name)
        assertEquals(lunch.setId, changed.setId)
        assertEquals(lunch.language, changed.language)
        assertEquals(now.plusSeconds(60).toEpochMilli(), changed.updatedAt)
        // Same place, same ids; every other recipe of both sets is exactly as it was.
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.filter { it.id != lunch.id }, after.filter { it.id != lunch.id })
        val state = vm.uiState.value
        assertNull(state.modify)
        assertTrue(state.isModified(lunch.id))
        assertFalse(state.isModifying(lunch.id))
        assertTrue(before.filter { it.id != lunch.id }.none { state.isModified(it.id) })
        // The request carried the lunch and the instruction.
        val user = client.requests.last().messages.single().content
        assertTrue(user.contains("\"name\":\"${lunch.name}\""))
        assertTrue(user.contains("<<<Make it vegetarian>>>"))
    }

    @Test
    fun `undo puts back each earlier version in turn`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }
        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        val vegetarian = vm.recipesOf(message).first { it.id == lunch.id }
        vm.modify(message, vegetarian, "Fewer calories")
        reply(fixture("modify_en_two_recipes.txt"))
        assertEquals("Light chicken lettuce wrap", vm.recipesOf(message).first { it.id == lunch.id }.name)

        vm.onUndoModify(message.id, lunch.id)
        assertEquals(vegetarian, vm.recipesOf(message).first { it.id == lunch.id })
        assertTrue(vm.uiState.value.isModified(lunch.id))

        vm.onUndoModify(message.id, lunch.id)
        assertEquals(message.recipes, vm.recipesOf(message))
        assertFalse(vm.uiState.value.isModified(lunch.id))

        vm.onUndoModify(message.id, lunch.id) // nothing left to undo
        assertEquals(message.recipes, vm.recipesOf(message))
    }

    @Test
    fun `a failed change leaves the card as it was and keeps the sheet open with the error`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }

        vm.modify(message, lunch, "Make it vegetarian")
        reply("Sorry, I can't do that.")
        reply(fixture("modify_en_invalid.txt"))

        assertEquals(message.recipes, vm.recipesOf(message))
        assertEquals(
            ModifyState(message.id, lunch.id, "Make it vegetarian", running = false, error = ModifyError.Rejected),
            vm.uiState.value.modify,
        )
        assertFalse(vm.uiState.value.isModified(lunch.id))

        // Sending again clears the error and can succeed.
        vm.onModifySend()
        assertNull(vm.uiState.value.modify?.error)
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))
        assertEquals(ModifyError.Llm(LlmErrorKind.Network), vm.uiState.value.modify?.error)
        assertEquals(message.recipes, vm.recipesOf(message))
    }

    @Test
    fun `cancel stops a running change and leaves the card as it was`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val (breakfast, lunch) = message.recipes
        client.cancelled = false
        vm.modify(message, lunch, "Make it vegetarian")
        client.events.send(LlmEvent.Delta("Here is"))

        // While it runs, no other change starts.
        vm.onModify(message.id, breakfast.id)
        assertEquals(lunch.id, vm.uiState.value.modify?.recipeId)

        vm.onModifyCancel()
        assertTrue(client.cancelled)
        assertNull(vm.uiState.value.modify)
        assertEquals(message.recipes, vm.recipesOf(message))
        assertFalse(vm.uiState.value.isModified(lunch.id))
    }

    @Test
    fun `send needs an instruction and an open sheet on a card that exists`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        vm.onModifySend() // no sheet
        vm.onModify(message.id, message.recipes.first().id)
        vm.onModifyInstructionChanged("   ")
        assertFalse(vm.uiState.value.modify!!.canSend)
        vm.onModifySend()
        vm.onModify(message.id, "no-such-recipe")
        vm.onModifyInstructionChanged("Vegetarian")
        vm.onModifySend()

        assertEquals(1, client.requests.size) // only the generation
        assertFalse(vm.uiState.value.modify!!.running)
    }

    @Test
    fun `a saved card that changes offers save changes, which replaces the saved copy`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }
        assertEquals(SaveState.UNSAVED, vm.uiState.value.saveState(lunch))
        vm.onSave(lunch)
        assertEquals(SaveState.SAVED, vm.uiState.value.saveState(lunch))

        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        val changed = vm.recipesOf(message).first { it.id == lunch.id }
        assertEquals(SaveState.CHANGED, vm.uiState.value.saveState(changed))
        assertEquals(lunch.name, recipeDao.recipes.single().name) // nothing saved behind the user's back

        // Undo back to the saved version: Saved again.
        vm.onUndoModify(message.id, lunch.id)
        assertEquals(SaveState.SAVED, vm.uiState.value.saveState(vm.recipesOf(message).first { it.id == lunch.id }))

        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        val again = vm.recipesOf(message).first { it.id == lunch.id }
        vm.onSave(again)
        assertEquals(SaveState.SAVED, vm.uiState.value.saveState(again))
        assertEquals(listOf(lunch.id), recipeDao.recipes.map { it.id })
        assertEquals("Chickpea salad wrap", recipeDao.recipes.single().name)

        // Undo now goes back to content that differs from the saved copy.
        vm.onUndoModify(message.id, lunch.id)
        assertEquals(SaveState.CHANGED, vm.uiState.value.saveState(vm.recipesOf(message).first { it.id == lunch.id }))
    }

    @Test
    fun `a changed card that was never saved still offers save`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }
        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))

        val changed = vm.recipesOf(message).first { it.id == lunch.id }
        assertEquals(SaveState.UNSAVED, vm.uiState.value.saveState(changed))
        vm.onSave(changed)
        assertEquals("Chickpea salad wrap", recipeDao.recipes.single().name)
        assertEquals(SaveState.SAVED, vm.uiState.value.saveState(changed))
    }

    @Test
    fun `a failed save does not count as the saved version`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }
        vm.onSave(lunch)
        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        val changed = vm.recipesOf(message).first { it.id == lunch.id }

        recipeDao.failWrites = true
        vm.onSave(changed)

        assertTrue(vm.uiState.value.saveFailed)
        assertEquals(SaveState.CHANGED, vm.uiState.value.saveState(changed))
    }

    /** The stored messages of the only conversation, as (position, role, text, state). */
    private fun stored() = chatDao.messages.sortedBy { it.position }.map { listOf(it.position, it.role, it.text, it.status) }

    @Test
    fun `a turn is stored at once, its reply as it streams and again when it finishes`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        assertEquals(listOf("Hi"), chatDao.conversations.map { it.title })
        assertEquals(chatDao.conversations.single().id, vm.uiState.value.conversationId)
        assertEquals(listOf(listOf(0L, Role.USER, "Hi", ReplyState.DONE)), stored())

        client.events.send(LlmEvent.Delta("Hel"))
        assertEquals(listOf(1L, Role.ASSISTANT, "Hel", ReplyState.STREAMING), stored()[1])

        client.events.send(LlmEvent.Delta("lo"))
        client.events.send(LlmEvent.Done(null))
        assertEquals(listOf(1L, Role.ASSISTANT, "Hello", ReplyState.DONE), stored()[1])
    }

    @Test
    fun `a stopped reply is stored stopped with its partial text`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hel"))
        vm.onStop()
        assertEquals(listOf(1L, Role.ASSISTANT, "Hel", ReplyState.STOPPED), stored()[1])
        assertEquals(ReplyState.STOPPED, vm.uiState.value.messages.last().replyState)
    }

    @Test
    fun `a failed reply is stored with its partial text, and an empty one not at all`() = runTest {
        val vm = newViewModel()
        vm.send("Hi")
        client.events.send(LlmEvent.Delta("Hel"))
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))
        assertEquals(listOf(1L, Role.ASSISTANT, "Hel", ReplyState.ERROR), stored()[1])

        vm.send("Again")
        client.events.send(LlmEvent.Error(LlmErrorKind.Network))
        assertEquals(listOf(0L, 1L, 2L), stored().map { it[0] })
    }

    @Test
    fun `a new view model restores the last conversation and carries on with it`() = runTest {
        val first = newViewModel()
        first.generate(GenerationScope.FULL_DAY_SET, 2)
        reply(fixture("en_full_day_x2.txt"))
        val before = first.uiState.value

        val vm = newViewModel()
        val state = vm.uiState.value
        assertEquals(before.messages, state.messages)
        assertEquals(before.conversationId, state.conversationId)
        assertFalse(state.composer.expanded)
        assertFalse(state.sending)

        vm.send("More")
        assertEquals(listOf(0L, 1L, 2L), stored().map { it[0] })
        assertEquals(1, chatDao.conversations.size)
        // The restored turns go to the model as history.
        assertEquals(3, client.requests.last().messages.size)
    }

    @Test
    fun `a reply cut off by the app stopping comes back stopped with its partial text`() = runTest {
        val first = newViewModel()
        first.send("Hi")
        client.events.send(LlmEvent.Delta("Try oat"))

        val vm = newViewModel()
        val reply = vm.uiState.value.messages.last()
        assertEquals("Try oat", reply.text)
        assertEquals(ReplyState.STOPPED, reply.replyState)
        assertFalse(vm.uiState.value.sending)
        assertNull(vm.uiState.value.streamingMessageId)
    }

    @Test
    fun `new chat starts empty, and its first turn starts another conversation`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        reply("Sure.")
        val firstId = vm.uiState.value.conversationId

        clock.instant = now.plusSeconds(60)
        vm.onNewChat()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertNull(vm.uiState.value.conversationId)
        assertTrue(vm.uiState.value.composer.expanded)

        vm.send("Two")
        val secondId = vm.uiState.value.conversationId
        assertTrue(secondId != firstId)
        assertEquals(listOf("Two", "One"), vm.uiState.value.conversations.map { it.title })
        // The new chat sends no history from the old one.
        assertEquals(1, client.requests.last().messages.size)
    }

    @Test
    fun `new chat during a reply stops it and stores it in the old conversation`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        client.events.send(LlmEvent.Delta("Par"))
        vm.onNewChat()

        assertTrue(client.cancelled)
        assertFalse(vm.uiState.value.sending)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(listOf(1L, Role.ASSISTANT, "Par", ReplyState.STOPPED), stored()[1])
    }

    @Test
    fun `a conversation reopened from the list shows its messages and is restored next time`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        reply("First reply")
        val firstId = vm.uiState.value.conversationId!!
        clock.instant = now.plusSeconds(60)
        vm.onNewChat()
        vm.send("Two")
        reply("Second reply")

        vm.onShowRecent()
        assertTrue(vm.uiState.value.showRecent)
        clock.instant = now.plusSeconds(120)
        vm.onOpenConversation(firstId)

        val state = vm.uiState.value
        assertFalse(state.showRecent)
        assertEquals(firstId, state.conversationId)
        assertEquals(listOf(Role.USER to "One", Role.ASSISTANT to "First reply"), state.texts)
        assertEquals(firstId, newViewModel().uiState.value.conversationId)

        vm.onShowRecent()
        vm.onOpenConversation(firstId) // already open: just closes the list
        assertFalse(vm.uiState.value.showRecent)
        assertEquals(state.messages, vm.uiState.value.messages)
    }

    @Test
    fun `deleting a conversation removes it, and deleting the open one starts an empty chat`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        reply("First")
        val firstId = vm.uiState.value.conversationId!!
        vm.onNewChat()
        vm.send("Two")
        reply("Second")
        val secondId = vm.uiState.value.conversationId!!

        vm.onDeleteConversation(firstId)
        assertEquals(listOf(secondId), vm.uiState.value.visibleConversations.map { it.id })
        assertEquals(secondId, vm.uiState.value.conversationId)
        // Deleting another chat deletes the pending one for good.
        vm.onShowRecent()
        vm.onDeleteConversation(secondId)
        assertEquals(listOf(secondId), vm.uiState.value.conversations.map { it.id })
        assertTrue(vm.uiState.value.visibleConversations.isEmpty())
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertNull(vm.uiState.value.conversationId)
        assertTrue(vm.uiState.value.showRecent)

        vm.onDeleteConversationShown(vm.uiState.value.pendingDelete!!)
        assertNull(vm.uiState.value.pendingDelete)
        assertTrue(vm.uiState.value.conversations.isEmpty())
        assertTrue(chatDao.messages.isEmpty())
    }

    @Test
    fun `undo puts a deleted chat back with its messages and opens it again`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        reply("First")
        val id = vm.uiState.value.conversationId!!
        val messages = vm.uiState.value.messages

        vm.onShowRecent()
        vm.onDeleteConversation(id)
        val pending = vm.uiState.value.pendingDelete!!
        assertTrue(pending.wasOpen)
        assertTrue(vm.uiState.value.visibleConversations.isEmpty())
        assertTrue(vm.uiState.value.messages.isEmpty())

        vm.onUndoDeleteConversation(pending)
        assertNull(vm.uiState.value.pendingDelete)
        assertEquals(listOf(id), vm.uiState.value.visibleConversations.map { it.id })
        assertEquals(id, vm.uiState.value.conversationId)
        assertEquals(messages.map { it.text }, vm.uiState.value.messages.map { it.text })
        assertTrue(chatDao.messages.isNotEmpty())

        // A late dismissal of the undone snackbar deletes nothing.
        vm.onDeleteConversationShown(pending)
        assertEquals(listOf(id), vm.uiState.value.conversations.map { it.id })
    }

    @Test
    fun `undo of a chat that was not open keeps the chat on screen`() = runTest {
        val vm = newViewModel()
        vm.send("One")
        reply("First")
        val firstId = vm.uiState.value.conversationId!!
        vm.onNewChat()
        vm.send("Two")
        reply("Second")
        val secondId = vm.uiState.value.conversationId!!

        vm.onDeleteConversation(firstId)
        vm.onUndoDeleteConversation(vm.uiState.value.pendingDelete!!)
        assertEquals(secondId, vm.uiState.value.conversationId)
        assertEquals(setOf(firstId, secondId), vm.uiState.value.visibleConversations.map { it.id }.toSet())
    }

    @Test
    fun `a changed recipe and its undo are stored, while the undo history is not`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val lunch = message.recipes.first { it.mealType == MealType.LUNCH }
        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        val changed = vm.recipesOf(message)

        val restored = newViewModel()
        assertEquals(changed, restored.recipesOf(message))
        assertFalse(restored.uiState.value.isModified(lunch.id))

        vm.onUndoModify(message.id, lunch.id)
        assertEquals(message.recipes, newViewModel().recipesOf(message))
    }

    @Test
    fun `saved and save changes are worked out from the library on restore`() = runTest {
        val vm = newViewModel()
        val message = twoDays(vm)
        val (breakfast, lunch) = message.recipes
        vm.onSave(breakfast)
        vm.onSave(lunch)
        vm.modify(message, lunch, "Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))

        val state = newViewModel().uiState.value
        val recipes = state.messages.last().recipes
        assertEquals(SaveState.SAVED, state.saveState(recipes[0]))
        assertEquals(SaveState.CHANGED, state.saveState(recipes[1]))
        assertEquals(SaveState.UNSAVED, state.saveState(recipes[2]))
    }

    @Test
    fun `a preset sends its request, sends its text, or fills the input`() = runTest {
        val vm = newViewModel()
        vm.onPreset(PresetAction.Generate(GenerationRequest(GenerationScope.BREAKFAST, 3, "quick")))
        assertEquals(GenerationRequest(GenerationScope.BREAKFAST, 3, "quick"), vm.uiState.value.messages.first().generation)
        val sent = client.requests.last().messages.last().content
        assertTrue(sent.contains("Scope: breakfast"))
        assertTrue(sent.contains("Count: 3"))
        assertTrue(sent.contains("<<<quick>>>"))

        vm.onPreset(PresetAction.Send("ignored while a reply runs"))
        assertEquals(1, client.requests.size)
        reply("Done.")

        vm.onInputChanged("draft")
        vm.onPreset(PresetAction.Send("Make the recipes above vegetarian."))
        assertTrue(client.requests.last().messages.last().content.startsWith("Make the recipes above vegetarian."))
        assertEquals("draft", vm.uiState.value.input)
        reply("Done.")

        vm.onPreset(PresetAction.Fill("In my fridge: "))
        assertEquals("In my fridge: ", vm.uiState.value.input)
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `retry replaces the stored reply`() = runTest {
        val vm = newViewModel()
        vm.send("Give me a breakfast recipe")
        reply(fixture("en_malformed.txt"))
        client.events.send(LlmEvent.Delta("still not json"))
        client.events.send(LlmEvent.Done(null))
        val failed = vm.uiState.value.messages.last()
        assertEquals(RecipeStatus.FAILED, failed.recipeStatus)

        vm.onRetry(failed.id)
        reply(fixture("en_breakfast.txt"))
        assertEquals(listOf(0L, 2L), stored().map { it[0] })
        assertEquals(1, newViewModel().uiState.value.messages.last().recipes.size)
    }

    @Test
    fun `when storage fails the chat goes on in memory`() = runTest {
        chatDao.failWrites = true
        val vm = newViewModel()
        vm.send("Hi")
        reply("Hello")
        assertEquals(listOf(Role.USER to "Hi", Role.ASSISTANT to "Hello"), vm.uiState.value.texts)
        assertNull(vm.uiState.value.conversationId)
        assertTrue(chatDao.conversations.isEmpty())
    }
}
