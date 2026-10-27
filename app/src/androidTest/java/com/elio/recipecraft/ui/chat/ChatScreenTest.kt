package com.elio.recipecraft.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.elio.recipecraft.R
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.chat.ChatRepository
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.ui.recipe.RecipeCardTestTags
import java.time.Clock
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real ChatScreen and ChatViewModel against a scripted LLM client. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = ScriptedClient()
    private var openedSettings = false

    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun close() = database.close()

    private fun setContent(result: LlmClientResult = LlmClientResult.Ready(client)) {
        val nutrition = object : NutritionLookups {
            override suspend fun current() = emptyList<NutritionLookup>()
            override fun observe() = flowOf(emptyList<NutritionLookup>())
        }
        val factory = FixedFactory(result)
        val promptBuilder = PromptBuilder(AndroidAssetReader(context), EnglishSettings, Dispatchers.IO)
        val assembler = RecipeAssembler(Clock.systemUTC())
        val viewModel = ChatViewModel(
            clientFactory = factory,
            promptBuilder = promptBuilder,
            parser = RecipeParser(),
            assembler = assembler,
            nutrition = nutrition,
            recipes = RecipeRepository(database.recipes(), nutrition, Dispatchers.IO),
            recipeModifier = RecipeModifier(factory, promptBuilder, RecipeParser(), assembler, nutrition, Clock.systemUTC()),
            chats = ChatRepository(database.chats(), Clock.systemUTC(), Dispatchers.IO),
        )
        compose.setContent {
            RecipeCraftTheme {
                ChatScreen(onOpenSettings = { openedSettings = true }, viewModel = viewModel)
            }
        }
    }

    private fun send(text: String) {
        compose.onNodeWithTag(ChatTestTags.INPUT).performTextInput(text)
        compose.onNodeWithText(context.getString(R.string.chat_send)).performClick()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun streamsTheReplyAndStopCancelsIt() {
        setContent()
        send("Breakfast idea?")
        compose.onNodeWithText("Breakfast idea?").assertIsDisplayed()

        client.events.trySend(LlmEvent.Delta("Try"))
        waitForText("Try")
        client.events.trySend(LlmEvent.Delta(" a tomato"))
        waitForText("Try a tomato")

        compose.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        compose.waitUntil(5_000) { client.cancelled }
        compose.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()

        client.events.trySend(LlmEvent.Delta(" omelette"))
        compose.waitForIdle()
        compose.onNodeWithText("Try a tomato").assertIsDisplayed()
    }

    @Test
    fun aRejectedKeyShowsTheLocalizedMessageAndALinkToSettings() {
        setContent()
        send("Hi")
        client.events.trySend(LlmEvent.Error(LlmErrorKind.InvalidKey))

        waitForText(context.getString(R.string.error_invalid_key))
        compose.onNodeWithText(context.getString(R.string.chat_open_settings)).performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun noModelAsksToPickOneInSettings() {
        setContent(LlmClientResult.Unavailable(LlmErrorKind.MissingModel))
        send("Hi")

        waitForText(context.getString(R.string.error_missing_model))
        compose.onNodeWithText("Hi").assertIsDisplayed() // still in the input, ready to resend
        compose.onNodeWithText(context.getString(R.string.chat_open_settings)).assertIsDisplayed()
    }

    @Test
    fun aRecipeReplyShowsItsProseAPlaceholderAndThenACard() {
        setContent()
        send("Give me a breakfast recipe")
        val reply = fixture("en_breakfast.txt")

        client.events.trySend(LlmEvent.Delta(reply.substringBefore("{")))
        waitForText(context.getString(R.string.chat_building_recipes))
        compose.onNodeWithText("Here is a warm, filling breakfast that takes about ten minutes.").assertIsDisplayed()
        compose.onAllNodes(hasText("```", substring = true)).assertCountEquals(0)

        client.events.trySend(LlmEvent.Delta("{" + reply.substringAfter("{")))
        client.events.trySend(LlmEvent.Done(null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeCardTestTags.CARD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Banana oatmeal with milk").assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_building_recipes)).assertDoesNotExist()
    }

    @Test
    fun aReplyThatCannotBeRepairedShowsTheRawTextAndRetry() {
        setContent()
        send("Give me a breakfast recipe")
        val bad = fixture("en_malformed.txt")
        client.events.trySend(LlmEvent.Delta(bad))
        client.events.trySend(LlmEvent.Done(null))
        waitForText(context.getString(R.string.chat_repairing_recipes))
        client.events.trySend(LlmEvent.Delta("still not json {\"recipes\": ["))
        client.events.trySend(LlmEvent.Done(null))

        waitForText(context.getString(R.string.chat_recipe_failed))
        compose.onNodeWithText(bad).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { client.requests.size == 3 }
    }

    @Test
    fun theComposerAsksForTwoFullDaySetsAndShowsThemUnderDayHeaders() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.composer_scope_full_day)).performClick()
        compose.onNodeWithTag(ComposerTestTags.INCREASE).performClick()
        compose.onNodeWithTag(ComposerTestTags.COUNT).assertTextEquals("2 sets")
        compose.onNodeWithTag(ComposerTestTags.PREFERENCES).performTextInput("vegetarian")
        compose.onNodeWithTag(ComposerTestTags.GENERATE).performClick()

        waitForText("Full-day set × 2 · vegetarian")
        compose.waitUntil(5_000) { client.requests.isNotEmpty() }
        assertTrue(client.requests.single().messages.single().content.contains("- Count: 2\n"))
        // The composer folds away so the reply has room.
        compose.onNodeWithTag(ComposerTestTags.GENERATE).assertDoesNotExist()

        client.events.trySend(LlmEvent.Delta(fixture("en_full_day_x2.txt")))
        client.events.trySend(LlmEvent.Done(null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeCardTestTags.CARD).fetchSemanticsNodes().size == 6 }
        compose.onAllNodesWithTag(ChatTestTags.DAY_HEADER).assertCountEquals(2)
        compose.onNodeWithText("Day 1").assertExists()
        compose.onNodeWithText("Day 2").assertExists()
        compose.onNodeWithTag(ChatTestTags.GENERATION_WARNING).assertDoesNotExist()
    }

    @Test
    fun aShortReplyKeepsItsRecipesAndWarns() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.meal_dinner)).performClick()
        repeat(3) { compose.onNodeWithTag(ComposerTestTags.INCREASE).performClick() }
        compose.onNodeWithTag(ComposerTestTags.COUNT).assertTextEquals("4 recipes")
        compose.onNodeWithTag(ComposerTestTags.GENERATE).performClick()

        waitForText("Dinner × 4")
        client.events.trySend(LlmEvent.Delta(fixture("en_dinner_x3.txt")))
        client.events.trySend(LlmEvent.Done(null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeCardTestTags.CARD).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithTag(ChatTestTags.GENERATION_WARNING).assertExists()
        compose.onNodeWithText("Asked for 4 recipes, got 3.").assertExists()
        compose.onAllNodesWithTag(ChatTestTags.DAY_HEADER).assertCountEquals(0)
    }

    @Test
    fun theComposerCollapsesAndExpandsAndTheStepperStaysInRange() {
        setContent()
        compose.onNodeWithTag(ComposerTestTags.DECREASE).assertIsNotEnabled()
        repeat(12) { compose.onNodeWithTag(ComposerTestTags.INCREASE).performClick() }
        compose.onNodeWithTag(ComposerTestTags.COUNT).assertTextEquals("10 sets")
        compose.onNodeWithTag(ComposerTestTags.INCREASE).assertIsNotEnabled()

        compose.onNodeWithTag(ComposerTestTags.TOGGLE).performClick()
        compose.onNodeWithTag(ComposerTestTags.GENERATE).assertDoesNotExist()
        compose.onNodeWithTag(ChatTestTags.INPUT).assertIsDisplayed()
        compose.onNodeWithTag(ComposerTestTags.TOGGLE).performClick()
        compose.onNodeWithTag(ComposerTestTags.GENERATE).assertIsDisplayed()
    }

    @Test
    fun modifyingTheLunchOfASetChangesOnlyThatCardAndUndoPutsItBack() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.composer_scope_full_day)).performClick()
        compose.onNodeWithTag(ComposerTestTags.INCREASE).performClick()
        compose.onNodeWithTag(ComposerTestTags.GENERATE).performClick()
        compose.waitUntil(5_000) { client.requests.isNotEmpty() }
        client.events.trySend(LlmEvent.Delta(fixture("en_full_day_x2.txt")))
        client.events.trySend(LlmEvent.Done(null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeCardTestTags.CARD).fetchSemanticsNodes().size == 6 }
        val others = listOf("Greek yogurt parfait", "Salmon with roasted vegetables", "Peanut butter toast", "Lentil soup", "Beef stir-fry")

        // Day 1 is breakfast, lunch, dinner, so the second card is the lunch.
        compose.onAllNodesWithTag(RecipeCardTestTags.MODIFY)[1].performScrollTo().performClick()
        compose.onNodeWithTag(ModifySheetTestTags.SHEET).assertIsDisplayed()
        compose.onNodeWithTag(ModifySheetTestTags.SEND).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.modify_chip_vegetarian)).performClick()
        compose.onNodeWithTag(ModifySheetTestTags.INSTRUCTION).assertTextContains("Make it vegetarian")
        compose.onNodeWithTag(ModifySheetTestTags.SEND).performClick()

        compose.waitUntil(5_000) { client.requests.size == 2 }
        assertTrue(client.requests.last().messages.single().content.contains("\"name\":\"Chicken salad wrap\""))
        assertTrue(client.requests.last().messages.single().content.contains("<<<Make it vegetarian>>>"))
        compose.onNodeWithTag(RecipeCardTestTags.MODIFYING).assertExists()
        client.events.trySend(LlmEvent.Delta(fixture("modify_en_lunch_vegetarian.txt")))
        client.events.trySend(LlmEvent.Done(null))

        waitForText("Chickpea salad wrap")
        compose.onNodeWithTag(ModifySheetTestTags.SHEET).assertDoesNotExist()
        compose.onNodeWithText("Chicken salad wrap").assertDoesNotExist()
        others.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithTag(RecipeCardTestTags.CARD).assertCountEquals(6)
        compose.onAllNodesWithTag(RecipeCardTestTags.MODIFIED).assertCountEquals(1)

        compose.onNodeWithTag(RecipeCardTestTags.UNDO).performScrollTo().performClick()
        waitForText("Chicken salad wrap")
        compose.onNodeWithText("Chickpea salad wrap").assertDoesNotExist()
        compose.onAllNodesWithTag(RecipeCardTestTags.MODIFIED).assertCountEquals(0)
        compose.onNodeWithTag(RecipeCardTestTags.UNDO).assertDoesNotExist()
    }

    @Test
    fun cancellingARunningChangeLeavesTheCardAsItWas() {
        setContent()
        send("Breakfast?")
        client.events.trySend(LlmEvent.Delta(fixture("en_breakfast.txt")))
        client.events.trySend(LlmEvent.Done(null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeCardTestTags.CARD).fetchSemanticsNodes().size == 1 }

        compose.onNodeWithTag(RecipeCardTestTags.MODIFY).performScrollTo().performClick()
        compose.onNodeWithTag(ModifySheetTestTags.INSTRUCTION).performTextInput("No honey")
        client.cancelled = false // the finished reply above counts as a cancelled collection
        compose.onNodeWithTag(ModifySheetTestTags.SEND).performClick()
        compose.waitUntil(5_000) { client.requests.size == 2 }
        compose.onNodeWithTag(ModifySheetTestTags.PROGRESS).assertExists()
        compose.onNodeWithTag(RecipeCardTestTags.SAVE).assertIsNotEnabled()

        compose.onNodeWithTag(ModifySheetTestTags.CANCEL).performClick()
        compose.waitUntil(5_000) { client.cancelled }
        compose.onNodeWithTag(ModifySheetTestTags.SHEET).assertDoesNotExist()
        compose.onNodeWithTag(RecipeCardTestTags.MODIFYING).assertDoesNotExist()
        compose.onNodeWithTag(RecipeCardTestTags.MODIFIED).assertDoesNotExist()
        compose.onNodeWithTag(RecipeCardTestTags.SAVE).assertIsEnabled()
        compose.onNodeWithText("Banana oatmeal with milk").assertExists()
    }

    @Test
    fun presetChipsOnAnEmptyChatSendTheirTemplateInTheAppLanguage() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.preset_full_day)).assertExists()
        // Follow-ups refer to recipes above, so an empty chat leaves them out.
        compose.onNodeWithTag(PresetChipsTestTags.chip(PresetChip.MAKE_IT_VEGETARIAN)).assertDoesNotExist()

        compose.onNodeWithTag(PresetChipsTestTags.chip(PresetChip.QUICK_BREAKFASTS)).performScrollTo().performClick()

        compose.waitUntil(5_000) { client.requests.isNotEmpty() }
        val sent = client.requests.single().messages.single().content
        assertTrue(sent.contains("- Scope: breakfast\n"))
        assertTrue(sent.contains("- Count: 3\n"))
        assertTrue(sent.contains("<<<${context.getString(R.string.preset_quick_breakfasts_preferences)}>>>"))
        waitForText("Breakfast × 3 · ${context.getString(R.string.preset_quick_breakfasts_preferences)}")
        // Once the chat has messages, the chips sit in a row above the input, follow-ups first.
        compose.onNodeWithTag(PresetChipsTestTags.chip(PresetChip.MAKE_IT_VEGETARIAN)).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun anOpenEndedChipFillsTheInputForEditing() {
        setContent()
        compose.onNodeWithTag(PresetChipsTestTags.chip(PresetChip.FRIDGE)).performScrollTo().performClick()
        compose.onNodeWithTag(ChatTestTags.INPUT).assertTextContains(context.getString(R.string.preset_fridge_text).trim(), substring = true)
        compose.waitForIdle()
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun newChatStartsEmptyAndRecentChatsReopensTheEarlierOne() {
        setContent()
        send("First question")
        client.events.trySend(LlmEvent.Delta("First answer"))
        client.events.trySend(LlmEvent.Done(null))
        waitForText("First answer")

        compose.onNodeWithTag(ChatTestTags.NEW_CHAT).performClick()
        compose.onNodeWithText("First answer").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_empty)).assertIsDisplayed()

        compose.onNodeWithTag(ChatTestTags.RECENT).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecentChatsTestTags.ITEM).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("First question").performClick()

        waitForText("First answer")
        compose.onNodeWithTag(RecentChatsTestTags.SHEET).assertDoesNotExist()
    }

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private object EnglishSettings : AppLanguageSettings {
        override val choice = AppLanguage.EN
        override val resolvedLanguage = Language.EN
        override fun setChoice(choice: AppLanguage) = Unit
    }

    private class ScriptedClient : LlmClient {
        val events = Channel<LlmEvent>(Channel.UNLIMITED)
        val requests: MutableList<LlmRequest> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var cancelled = false

        override fun stream(request: LlmRequest): Flow<LlmEvent> =
            events.receiveAsFlow().onStart { requests += request }.onCompletion { if (it is CancellationException) cancelled = true }

        override suspend fun listModels() = ModelListResult.Success(emptyList())
    }

    private class FixedFactory(private val result: LlmClientResult) : LlmClientFactory {
        override suspend fun forActiveProvider(requireModel: Boolean) = result
        override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = result
    }
}
