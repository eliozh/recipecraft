package com.elio.recipecraft.ui.recipe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import com.elio.recipecraft.ui.assertTouchHeightIsAtLeast
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.data.recipe.AndroidRecipeDocuments
import com.elio.recipecraft.data.recipe.ExportImportService
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.ui.AppLayout
import com.elio.recipecraft.ui.LocalAppLayout
import com.elio.recipecraft.ui.NavigationType
import com.elio.recipecraft.ui.TopLevelDestination
import com.elio.recipecraft.ui.chat.ModifySheetTestTags
import com.elio.recipecraft.ui.library.LibraryTestTags
import com.elio.recipecraft.ui.library.LibraryViewModel
import com.elio.recipecraft.ui.libraryGraph
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens a saved recipe from the real library graph: checks every section, deletes it and undoes
 * that, and changes it with Modify with AI against a scripted model, by Replace and Save as copy.
 */
@RunWith(AndroidJUnit4::class)
class RecipeDetailTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun close() = database.close()

    private val omelette = Recipe(
        id = "omelette-1",
        name = "Cheese omelette",
        mealType = MealType.BREAKFAST,
        servings = 2,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 420),
        // Long enough that the list has to scroll.
        ingredients = listOf(Ingredient("Eggs", "egg", "3", "", 150.0, 210)) +
            (1..20).map { Ingredient("Herb $it", "herb $it", "1", "pinch", null, null) } +
            Ingredient("Saffron", "saffron", "1", "pinch", 0.5, 2),
        tools = listOf("Non-stick pan", "Whisk"),
        steps = listOf(Step(1, "Whisk the eggs.", 2), Step(2, "Cook until set.", null)),
        language = Language.EN,
        setId = null,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    private val toast = omelette.copy(id = "toast-1", name = "Peanut butter toast", createdAt = 1_600_000_000_000)

    private val eggsOnly = object : NutritionLookups {
        private val lookups = listOf<NutritionLookup>(TableLookup(IngredientMatcher(listOf(NutritionFood("egg", emptyList(), 143.0)))))
        override suspend fun current() = lookups
        override fun observe() = flowOf(lookups)
    }

    private val repository by lazy { RecipeRepository(database.recipes(), eggsOnly, Dispatchers.IO) }

    /** English, the language of every recipe here, so the screen offers no Translate. */
    private val english = object : AppLanguageSettings {
        override val choice = AppLanguage.EN
        override val resolvedLanguage = Language.EN
        override fun setChoice(choice: AppLanguage) = Unit
    }

    private val translations by lazy {
        val noKey = object : LlmClientFactory {
            override suspend fun forActiveProvider(requireModel: Boolean) = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
            override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = forActiveProvider(requireModel)
        }
        val translator = RecipeTranslator(PromptBuilder(AndroidAssetReader(context), english, Dispatchers.IO), RecipeParser(), Clock.systemUTC())
        RecipeTranslations(noKey, translator, repository, CoroutineScope(Dispatchers.Default))
    }

    /** Each request takes the next reply sent here. */
    private val replies = Channel<String>(Channel.UNLIMITED)
    private val modifier by lazy {
        val client = object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                emit(LlmEvent.Delta(replies.receive()))
                emit(LlmEvent.Done(null))
            }

            override suspend fun listModels() = ModelListResult.Success(emptyList())
        }
        val ready = object : LlmClientFactory {
            override suspend fun forActiveProvider(requireModel: Boolean) = LlmClientResult.Ready(client)
            override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = forActiveProvider(requireModel)
        }
        val promptBuilder = PromptBuilder(AndroidAssetReader(context), english, Dispatchers.IO)
        RecipeModifier(ready, promptBuilder, RecipeParser(), RecipeAssembler(Clock.systemUTC()), eggsOnly, Clock.systemUTC())
    }

    private fun setContent(layout: AppLayout = AppLayout.Compact) {
        val undoDelete = UndoDelete()
        val library = LibraryViewModel(repository, undoDelete, ExportImportService(repository, Clock.systemUTC(), Dispatchers.IO), AndroidRecipeDocuments(context))
        compose.setContent {
            RecipeCraftTheme {
                CompositionLocalProvider(LocalAppLayout provides layout) {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = TopLevelDestination.LIBRARY.route) {
                        libraryGraph(
                            navController,
                            onOpenChat = {},
                            libraryViewModel = { library },
                            detailViewModel = { viewModel { RecipeDetailViewModel(createSavedStateHandle(), repository, undoDelete, english, translations, modifier, Clock.systemUTC()) } },
                            paneDetailViewModel = { id ->
                                viewModel(key = id) {
                                    val args = SavedStateHandle(mapOf(RecipeDetailViewModel.ARG_RECIPE_ID to id))
                                    RecipeDetailViewModel(args, repository, undoDelete, english, translations, modifier, Clock.systemUTC())
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String, count: Int = 1) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == count }

    private fun scrollTo(tag: String) = compose.onNodeWithTag(RecipeDetailTestTags.SCREEN).performScrollToNode(hasTestTag(tag))

    @Test
    fun detailShowsEverySectionAndDeleteCanBeUndone() {
        runBlocking { repository.save(listOf(omelette, toast)) }
        val saved = runBlocking { database.recipes().all().single { it.id == omelette.id } }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 2)

        // Search first, to check the library keeps it across the detail screen.
        compose.onNodeWithTag(LibraryTestTags.SEARCH).performTextInput("cheese")
        waitForTag(LibraryTestTags.ITEM, 1)
        compose.onNodeWithText("Cheese omelette").performClick()

        waitForTag(RecipeDetailTestTags.NAME)
        compose.waitForIdle()
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assertTextContains("Cheese omelette")
        compose.onNodeWithTag(RecipeDetailTestTags.META)
            .assertTextContains(
                listOf(
                    context.getString(R.string.meal_breakfast),
                    context.resources.getQuantityString(R.plurals.recipe_servings, 2, 2),
                    context.getString(R.string.language_english),
                ).joinToString(" · "),
            )
        compose.onNodeWithTag(RecipeDetailTestTags.DATES).assertTextContains("Created", substring = true)
        compose.onNodeWithTag(RecipeCardTestTags.CALORIES).assertTextContains("420 kcal total · 210 kcal per serving")
        compose.onNodeWithTag(RecipeDetailTestTags.DISCLAIMER).assertTextContains(context.getString(R.string.recipe_calories_disclaimer))
        // Eggs matched the table: 150 g × 143 / 100; saffron did not, so it shows the model's figure.
        compose.onNodeWithText("3 Eggs").assertExists()
        compose.onNodeWithText("150 g · 215 kcal").assertExists()
        scrollTo(RecipeDetailTestTags.TOOLS)
        compose.onNodeWithText("1 pinch Saffron").assertExists()
        compose.onNodeWithText("0.5 g · ≈ 2 kcal (AI)").assertExists()
        compose.onNodeWithText("• Whisk").assertExists()
        scrollTo(RecipeDetailTestTags.STEPS)
        compose.onNodeWithTag(RecipeDetailTestTags.SCREEN).performScrollToNode(hasText("Cook until set."))
        compose.onNodeWithText("Whisk the eggs.").assertExists()
        compose.onNodeWithText("2 min").assertExists()

        // Back keeps the search.
        compose.onNodeWithTag(RecipeDetailTestTags.BACK).performClick()
        compose.waitForIdle()
        waitForTag(LibraryTestTags.ITEM, 1)
        compose.onNodeWithTag(LibraryTestTags.SEARCH).assertTextContains("cheese")

        // Delete, then Undo from the library's snackbar.
        compose.onNodeWithText("Cheese omelette").performClick()
        waitForTag(RecipeDetailTestTags.DELETE)
        compose.onNodeWithTag(RecipeDetailTestTags.DELETE).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.recipe_deleted))).fetchSemanticsNodes().isNotEmpty()
        }
        waitForTag(LibraryTestTags.NO_MATCH)
        assertEquals(listOf(toast.id), runBlocking { database.recipes().all().map { it.id } })

        compose.onNodeWithText(context.getString(R.string.action_undo)).performClick()
        waitForTag(LibraryTestTags.ITEM, 1)
        compose.onNodeWithText("Cheese omelette").assertExists()
        assertEquals(saved, runBlocking { database.recipes().all().single { it.id == omelette.id } })
    }

    @Test
    fun aFailedTranslateShowsItsSnackbar() {
        // The flag was once cleared before the snackbar showed, which cancelled it.
        val chinese = omelette.copy(id = "zh-1", name = "芝士煎蛋卷", language = Language.ZH)
        runBlocking { repository.save(listOf(chinese)) }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 1)
        compose.onNodeWithText("芝士煎蛋卷").performClick()
        waitForTag(RecipeDetailTestTags.TRANSLATE)
        compose.onNodeWithTag(RecipeDetailTestTags.TRANSLATE).performClick()

        val message = context.getString(R.string.error_missing_key)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun theLibraryAndDetailPassAccessibilityChecks() {
        compose.enableAccessibilityChecks()
        runBlocking { repository.save(listOf(omelette, toast)) }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 2)
        compose.onNodeWithText(context.getString(R.string.library_filter_all)).assertIsSelected().assertTouchHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(LibraryTestTags.MENU).assertTouchHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cheese omelette").performClick()

        waitForTag(RecipeDetailTestTags.NAME)
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assert(isHeading())
        compose.onNodeWithTag(RecipeDetailTestTags.INGREDIENTS).assert(isHeading())
        compose.onNodeWithTag(RecipeDetailTestTags.BACK)
            .assertContentDescriptionEquals(context.getString(R.string.action_back))
            .assertTouchHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(RecipeDetailTestTags.SHARE)
            .assertContentDescriptionEquals(context.getString(R.string.recipe_share))
            .assertTouchHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(RecipeDetailTestTags.DELETE)
            .assertContentDescriptionEquals(context.getString(R.string.recipe_delete))
            .assertTouchHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(RecipeDetailTestTags.MODIFY).assertTouchHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ModifySheetTestTags.SHEET).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(context.getString(R.string.recipe_modify)) and isHeading()).assertExists()
    }

    @Test
    fun aWideWindowShowsTheRecipeBesideTheList() {
        runBlocking { repository.save(listOf(omelette, toast)) }
        setContent(AppLayout(NavigationType.RAIL, listDetail = true, compactHeight = false))
        // The list pane is narrow on a phone, so only the first item may be laid out.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Cheese omelette")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.library_select_recipe)).assertExists()

        compose.onNodeWithText("Cheese omelette").performClick()
        waitForTag(RecipeDetailTestTags.NAME)
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assertTextContains("Cheese omelette")
        // The list stays beside it, with the open recipe marked.
        compose.onNodeWithTag(LibraryTestTags.SEARCH).assertExists()
        compose.onAllNodesWithTag(LibraryTestTags.ITEM).filterToOne(hasText("Cheese omelette")).assertIsSelected()

        compose.onNodeWithTag(LibraryTestTags.LIST).performScrollToNode(hasText("Peanut butter toast"))
        compose.onNodeWithText("Peanut butter toast").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(RecipeDetailTestTags.NAME) and hasText("Peanut butter toast")).fetchSemanticsNodes().size == 1
        }

        // Back closes the pane; the list is still there.
        compose.onNodeWithTag(RecipeDetailTestTags.BACK).performClick()
        waitForTag(RecipeDetailTestTags.NAME, 0)
        compose.onNodeWithText(context.getString(R.string.library_select_recipe)).assertExists()

        // Delete from the pane closes it and the library offers Undo.
        compose.onNodeWithTag(LibraryTestTags.LIST).performScrollToNode(hasText("Cheese omelette"))
        compose.onNodeWithText("Cheese omelette").performClick()
        waitForTag(RecipeDetailTestTags.DELETE)
        compose.onNodeWithTag(RecipeDetailTestTags.DELETE).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.recipe_deleted))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Cheese omelette")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(context.getString(R.string.library_select_recipe)).assertExists()
    }

    @Test
    fun anUnknownIdSaysTheRecipeIsGone() {
        compose.setContent {
            RecipeCraftTheme {
                RecipeDetailScreen(
                    onBack = {},
                    onDeleted = {},
                    onOpenRecipe = {},
                    viewModel = viewModel {
                        RecipeDetailViewModel(
                            SavedStateHandle(mapOf(RecipeDetailViewModel.ARG_RECIPE_ID to "gone")),
                            repository,
                            UndoDelete(),
                            english,
                            translations,
                            modifier,
                            Clock.systemUTC(),
                        )
                    },
                )
            }
        }
        waitForTag(RecipeDetailTestTags.NOT_FOUND)
        compose.onNodeWithText(context.getString(R.string.recipe_not_found)).assertExists()
    }

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** From the library: open the omelette, ask for it vegetarian with the chip, and wait for the preview. */
    private fun previewVegetarianOmelette() {
        compose.onNodeWithText("Cheese omelette").performClick()
        waitForTag(RecipeDetailTestTags.MODIFY)
        compose.onNodeWithTag(RecipeDetailTestTags.MODIFY).performClick()
        waitForTag(ModifySheetTestTags.SHEET)
        compose.onNodeWithText(context.getString(R.string.modify_chip_vegetarian)).performClick()
        compose.onNodeWithTag(ModifySheetTestTags.INSTRUCTION).assertTextContains("Make it vegetarian")
        replies.trySend(fixture("modify_en_lunch_vegetarian.txt"))
        compose.onNodeWithTag(ModifySheetTestTags.SEND).performClick()
        waitForTag(ModifyPreviewTestTags.SHEET)
        compose.onNodeWithTag(ModifySheetTestTags.SHEET).assertDoesNotExist()
        compose.onNodeWithTag(ModifyPreviewTestTags.NAME).assertTextContains("After: Chickpea salad wrap", substring = true)
        // Every omelette ingredient is removed and the wrap's are added further down the list.
        compose.onNodeWithTag(ModifyPreviewTestTags.LIST).performScrollToNode(hasTestTag(ModifyPreviewTestTags.REMOVED))
        compose.onNodeWithTag(ModifyPreviewTestTags.LIST).performScrollToNode(hasTestTag(ModifyPreviewTestTags.ADDED))
        compose.onNodeWithTag(ModifyPreviewTestTags.LIST).performScrollToNode(hasContentDescription("Added: 150 g Chickpeas"))
    }

    @Test
    fun modifyThenReplaceUpdatesTheSavedRecipeInPlace() {
        runBlocking { repository.save(listOf(omelette, toast)) }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 2)

        // Cancel first: nothing changes.
        previewVegetarianOmelette()
        compose.onNodeWithTag(ModifyPreviewTestTags.CANCEL).performClick()
        waitForTag(ModifyPreviewTestTags.SHEET, 0)
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assertTextContains("Cheese omelette")
        assertEquals("Cheese omelette", runBlocking { database.recipes().all().single { it.id == omelette.id }.name })
        compose.onNodeWithTag(RecipeDetailTestTags.BACK).performClick()
        waitForTag(LibraryTestTags.ITEM, 2)

        previewVegetarianOmelette()
        compose.onNodeWithTag(ModifyPreviewTestTags.REPLACE).performClick()
        waitForTag(ModifyPreviewTestTags.SHEET, 0)
        waitForText("Chickpea salad wrap")
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assertTextContains("Chickpea salad wrap")
        waitForText(context.getString(R.string.recipe_replaced))
        compose.onNodeWithTag(RecipeDetailTestTags.DATES).assertTextContains("Updated", substring = true)

        val saved = runBlocking { database.recipes().all().map { it.toDomain() } }
        assertEquals(2, saved.size)
        val replaced = saved.single { it.id == omelette.id }
        assertEquals("Chickpea salad wrap", replaced.name)
        assertEquals(omelette.createdAt, replaced.createdAt)
        assertTrue(replaced.updatedAt > omelette.updatedAt)
    }

    @Test
    fun modifyThenSaveAsCopyAddsARecipeAndKeepsTheOriginal() {
        runBlocking { repository.save(listOf(omelette)) }
        val original = runBlocking { database.recipes().all().single() }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 1)

        previewVegetarianOmelette()
        compose.onNodeWithTag(ModifyPreviewTestTags.SAVE_AS_COPY).performClick()
        waitForTag(ModifyPreviewTestTags.SHEET, 0)
        waitForText(context.getString(R.string.recipe_saved_as_copy))
        // The original is still on screen and unchanged.
        compose.onNodeWithTag(RecipeDetailTestTags.NAME).assertTextContains("Cheese omelette")
        val all = runBlocking { database.recipes().all() }
        assertEquals(2, all.size)
        assertEquals(original, all.single { it.id == omelette.id })
        val copy = all.single { it.id != omelette.id }.toDomain()
        assertNotEquals(omelette.id, copy.id)
        assertNull(copy.setId)

        // Open goes to the copy; back twice reaches the library, which holds both.
        compose.onNodeWithText(context.getString(R.string.action_open)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(RecipeDetailTestTags.NAME) and hasText("Chickpea salad wrap")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(RecipeDetailTestTags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RecipeDetailTestTags.BACK).performClick()
        waitForTag(LibraryTestTags.ITEM, 2)
        compose.onNodeWithText("Chickpea salad wrap").assertExists()
        compose.onNodeWithText("Cheese omelette").assertExists()
    }
}
