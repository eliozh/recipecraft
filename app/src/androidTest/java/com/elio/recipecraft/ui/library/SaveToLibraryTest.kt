package com.elio.recipecraft.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.chat.ChatRepository
import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.ui.chat.ChatScreen
import com.elio.recipecraft.ui.chat.ChatTestTags
import com.elio.recipecraft.ui.chat.ChatViewModel
import com.elio.recipecraft.ui.chat.ComposerTestTags
import com.elio.recipecraft.ui.recipe.RecipeCardTestTags
import com.elio.recipecraft.ui.recipe.UndoDelete
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saves recipes from the real ChatScreen and finds them in the real LibraryScreen, over an in-memory database. */
@RunWith(AndroidJUnit4::class)
class SaveToLibraryTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val events = Channel<LlmEvent>(Channel.UNLIMITED)
    private var showLibrary by mutableStateOf(true)

    @After
    fun close() = database.close()

    private fun setContent() {
        val noTables = object : NutritionLookups {
            override suspend fun current() = emptyList<NutritionLookup>()
            override fun observe() = flowOf(emptyList<NutritionLookup>())
        }
        val repository = RecipeRepository(database.recipes(), noTables, Dispatchers.IO)
        val client = object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmEvent> = events.receiveAsFlow()
            override suspend fun listModels() = ModelListResult.Success(emptyList())
        }
        val factory = object : LlmClientFactory {
            override suspend fun forActiveProvider(requireModel: Boolean) = LlmClientResult.Ready(client)
            override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = LlmClientResult.Ready(client)
        }
        val promptBuilder = PromptBuilder(AndroidAssetReader(context), EnglishSettings, Dispatchers.IO)
        val assembler = RecipeAssembler(Clock.systemUTC())
        val chat = ChatViewModel(
            clientFactory = factory,
            promptBuilder = promptBuilder,
            parser = RecipeParser(),
            assembler = assembler,
            nutrition = noTables,
            recipes = repository,
            recipeModifier = RecipeModifier(factory, promptBuilder, RecipeParser(), assembler, noTables, Clock.systemUTC()),
            chats = ChatRepository(database.chats(), Clock.systemUTC(), Dispatchers.IO),
        )
        val library = LibraryViewModel(repository, UndoDelete())
        compose.setContent {
            RecipeCraftTheme {
                if (showLibrary) {
                    LibraryScreen(onOpenChat = { showLibrary = false }, onOpenRecipe = {}, viewModel = library)
                } else {
                    ChatScreen(onOpenSettings = {}, viewModel = chat)
                }
            }
        }
    }

    private fun waitForTagCount(tag: String, count: Int) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == count }

    @Test
    fun savedRecipesShowInTheLibraryAndCanBeFilteredAndSearched() {
        setContent()
        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertExists()
        compose.onNodeWithText(context.getString(R.string.library_open_chat)).performClick()

        // Two full-day sets from the composer.
        compose.onNodeWithText(context.getString(R.string.composer_scope_full_day)).performClick()
        compose.onNodeWithTag(ComposerTestTags.INCREASE).performClick()
        compose.onNodeWithTag(ComposerTestTags.GENERATE).performClick()
        events.trySend(LlmEvent.Delta(fixture("en_full_day_x2.txt")))
        events.trySend(LlmEvent.Done(null))
        waitForTagCount(RecipeCardTestTags.CARD, 6)

        // Save all of Day 1, then the first card of Day 2 on its own.
        compose.onAllNodesWithTag(ChatTestTags.SAVE_ALL).onFirst().performScrollTo().performClick()
        waitForTagCount(RecipeCardTestTags.SAVED, 3)
        compose.onAllNodesWithTag(ChatTestTags.SAVE_ALL).onFirst().assertIsNotEnabled()
        compose.onAllNodesWithTag(RecipeCardTestTags.SAVE).onFirst().performScrollTo().performClick()
        waitForTagCount(RecipeCardTestTags.SAVED, 4)
        compose.onAllNodesWithTag(RecipeCardTestTags.SAVE).assertCountEquals(2)

        compose.runOnIdle { showLibrary = true }
        waitForTagCount(LibraryTestTags.ITEM, 4)
        compose.onNodeWithText("Greek yogurt parfait").assertExists()
        compose.onNodeWithText("Peanut butter toast").assertExists()

        // Breakfast only.
        compose.onNodeWithText(context.getString(R.string.meal_breakfast)).performClick()
        waitForTagCount(LibraryTestTags.ITEM, 2)

        // An ingredient of the toast, in another case.
        compose.onNodeWithTag(LibraryTestTags.SEARCH).performTextInput("BREAD")
        waitForTagCount(LibraryTestTags.ITEM, 1)
        compose.onNodeWithText("Peanut butter toast").assertExists()

        // A dinner while the breakfast filter is on matches nothing.
        compose.onNodeWithTag(LibraryTestTags.SEARCH).performTextClearance()
        compose.onNodeWithTag(LibraryTestTags.SEARCH).performTextInput("salmon")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(LibraryTestTags.NO_MATCH).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.library_filter_all)).performClick()
        waitForTagCount(LibraryTestTags.ITEM, 1)
        compose.onAllNodes(hasText("Salmon with roasted vegetables")).assertCountEquals(1)
    }

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private object EnglishSettings : AppLanguageSettings {
        override val choice = AppLanguage.EN
        override val resolvedLanguage = Language.EN
        override fun setChoice(choice: AppLanguage) = Unit
    }
}
