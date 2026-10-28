package com.elio.recipecraft.ui.cover

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.recipe.AndroidRecipeDocuments
import com.elio.recipecraft.data.recipe.ExportImportService
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
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
import com.elio.recipecraft.ui.library.LibraryViewModel
import com.elio.recipecraft.ui.libraryGraph
import com.elio.recipecraft.ui.recipe.RecipeDetailTestTags
import com.elio.recipecraft.ui.recipe.RecipeDetailViewModel
import com.elio.recipecraft.ui.recipe.UndoDelete
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cook mode from the library's detail screen: paging through the steps with the buttons and by
 * swiping, the step indicator, a timer on a clock the test moves, the screen kept on, and the
 * step and timer kept when the composition is rebuilt from saved state.
 */
@RunWith(AndroidJUnit4::class)
class CookModeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun close() = database.close()

    private val noTables = object : NutritionLookups {
        override suspend fun current() = emptyList<NutritionLookup>()
        override fun observe() = flowOf(emptyList<NutritionLookup>())
    }
    private val repository by lazy { RecipeRepository(database.recipes(), noTables, Dispatchers.IO) }

    private val english = object : AppLanguageSettings {
        override val choice = AppLanguage.EN
        override val resolvedLanguage = Language.EN
        override fun setChoice(choice: AppLanguage) = Unit
    }

    private val noKey = object : LlmClientFactory {
        override suspend fun forActiveProvider(requireModel: Boolean) = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = forActiveProvider(requireModel)
    }

    /** Moved by hand: the timers read it, so a minute passes in one line. */
    @Volatile
    private var now = 1_000_000L
    private val clock = ElapsedClock { now }

    private val soup = Recipe(
        id = "soup-1",
        name = "Tomato soup",
        mealType = MealType.LUNCH,
        servings = 2,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 300),
        ingredients = listOf(Ingredient("Tomatoes", "tomato", "4", "", 400.0, 80)),
        tools = listOf("Pot"),
        steps = listOf(
            Step(1, "Chop the tomatoes.", null),
            Step(2, "Simmer the tomatoes with the stock.", 2),
            Step(3, "Blend until smooth.", null),
        ),
        language = Language.EN,
        setId = null,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    private lateinit var view: View

    private fun setLibraryContent(layout: AppLayout = AppLayout.Compact) {
        val undoDelete = UndoDelete()
        val library = LibraryViewModel(repository, undoDelete, ExportImportService(repository, Clock.systemUTC(), Dispatchers.IO), AndroidRecipeDocuments(context))
        val promptBuilder = PromptBuilder(AndroidAssetReader(context), english, Dispatchers.IO)
        val translations = RecipeTranslations(
            noKey,
            RecipeTranslator(promptBuilder, RecipeParser(), Clock.systemUTC()),
            repository,
            CoroutineScope(Dispatchers.Default),
        )
        val modifier = RecipeModifier(noKey, promptBuilder, RecipeParser(), RecipeAssembler(Clock.systemUTC()), noTables, Clock.systemUTC())
        compose.setContent {
            view = LocalView.current
            RecipeCraftTheme {
                CompositionLocalProvider(LocalAppLayout provides layout) {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = TopLevelDestination.LIBRARY.route) {
                        libraryGraph(
                            navController,
                            onOpenChat = {},
                            libraryViewModel = { library },
                            detailViewModel = {
                                viewModel { RecipeDetailViewModel(createSavedStateHandle(), repository, undoDelete, english, translations, modifier, Clock.systemUTC()) }
                            },
                            paneDetailViewModel = { owner, id ->
                                viewModel(viewModelStoreOwner = owner, key = id) {
                                    val args = SavedStateHandle(mapOf(RecipeDetailViewModel.ARG_RECIPE_ID to id))
                                    RecipeDetailViewModel(args, repository, undoDelete, english, translations, modifier, Clock.systemUTC())
                                }
                            },
                            cookViewModel = { viewModel { CookModeViewModel(createSavedStateHandle(), repository, clock) } },
                        )
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun openCookMode() {
        waitForText("Tomato soup")
        compose.onNodeWithText("Tomato soup").performClick()
        waitForTag(RecipeDetailTestTags.COOK)
        compose.onNodeWithTag(RecipeDetailTestTags.COOK).performClick()
        waitForTag(CookTestTags.INDICATOR)
    }

    private fun assertStep(position: Int, text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("$position / 3")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CookTestTags.INDICATOR).assertTextEquals("$position / 3")
        compose.onNodeWithTag(CookTestTags.INDICATOR, useUnmergedTree = true)
            .assert(hasContentDescription(context.getString(R.string.cook_step_of, position, 3)))
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun pagesThroughTheStepsWithButtonsAndSwipesAndKeepsTheScreenOn() {
        runBlocking { repository.save(listOf(soup)) }
        setLibraryContent()
        openCookMode()

        assertTrue(view.keepScreenOn)
        assertStep(1, "Chop the tomatoes.")
        compose.onNodeWithTag(CookTestTags.PREVIOUS).assertIsNotEnabled()
        // The first step gives no minutes, so it has no timer.
        compose.onNodeWithTag(CookTestTags.TIMER).assertDoesNotExist()

        compose.onNodeWithTag(CookTestTags.NEXT).performClick()
        assertStep(2, "Simmer the tomatoes with the stock.")
        compose.onNodeWithTag(CookTestTags.PREVIOUS).assertIsEnabled()

        compose.onNodeWithTag(CookTestTags.PAGER).performTouchInput { swipeLeft() }
        assertStep(3, "Blend until smooth.")
        compose.onNodeWithTag(CookTestTags.DONE).assertExists()

        compose.onNodeWithTag(CookTestTags.PAGER).performTouchInput { swipeRight() }
        assertStep(2, "Simmer the tomatoes with the stock.")
        compose.onNodeWithTag(CookTestTags.PREVIOUS).performClick()
        assertStep(1, "Chop the tomatoes.")

        // Done on the last step leaves Cook mode for the recipe, and lets the screen sleep again.
        compose.onNodeWithTag(CookTestTags.NEXT).performClick()
        compose.onNodeWithTag(CookTestTags.NEXT).performClick()
        assertStep(3, "Blend until smooth.")
        compose.onNodeWithTag(CookTestTags.DONE).performClick()
        waitForTag(RecipeDetailTestTags.COOK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CookTestTags.SCREEN).fetchSemanticsNodes().isEmpty() }
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun theTimerStartsPausesFinishesAndResets() {
        runBlocking { repository.save(listOf(soup)) }
        setLibraryContent()
        openCookMode()
        compose.onNodeWithTag(CookTestTags.NEXT).performClick()
        assertStep(2, "Simmer the tomatoes with the stock.")
        val time = compose.onNodeWithTag(CookTestTags.TIMER_TIME)
        time.assertTextEquals("2:00")
        compose.onNodeWithTag(CookTestTags.TIMER_RESET).assertIsNotEnabled()

        compose.onNodeWithTag(CookTestTags.TIMER_START).performClick()
        waitForTag(CookTestTags.TIMER_PAUSE)
        now += 30_000
        waitForText("1:30")

        compose.onNodeWithTag(CookTestTags.TIMER_PAUSE).performClick()
        waitForTag(CookTestTags.TIMER_START)
        now += 60_000
        compose.waitForIdle()
        time.assertTextEquals("1:30")
        compose.onNodeWithTag(CookTestTags.TIMER_START)
            .assert(hasContentDescription(context.getString(R.string.cook_timer_resume)))

        compose.onNodeWithTag(CookTestTags.TIMER_START).performClick()
        now += 90_000
        waitForTag(CookTestTags.TIMER_FINISHED)
        time.assertTextEquals("0:00")
        compose.onNodeWithText(context.getString(R.string.cook_timer_finished)).assertExists()

        compose.onNodeWithTag(CookTestTags.TIMER_RESET).performClick()
        waitForText("2:00")
        compose.onNodeWithTag(CookTestTags.TIMER_FINISHED).assertDoesNotExist()
    }

    @Test
    fun theStepAndTimerSurviveRestoringTheComposition() {
        runBlocking { repository.save(listOf(soup)) }
        val handle = SavedStateHandle(mapOf(CookModeViewModel.ARG_RECIPE_ID to soup.id))
        val viewModel = CookModeViewModel(handle, repository, clock)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecipeCraftTheme {
                CookModeScreen(onClose = {}, viewModel = viewModel)
            }
        }
        waitForTag(CookTestTags.INDICATOR)
        compose.onNodeWithTag(CookTestTags.NEXT).performClick()
        assertStep(2, "Simmer the tomatoes with the stock.")
        compose.onNodeWithTag(CookTestTags.TIMER_START).performClick()
        now += 15_000
        waitForText("1:45")

        restoration.emulateSavedInstanceStateRestore()

        assertStep(2, "Simmer the tomatoes with the stock.")
        compose.onNodeWithTag(CookTestTags.TIMER_PAUSE).assertExists()
        now += 15_000
        waitForText("1:30")
    }

    @Test
    fun theCoverDetailFoldsItsSectionsBehindABigCookButton() {
        runBlocking { repository.save(listOf(soup)) }
        setLibraryContent(AppLayout(NavigationType.BOTTOM_BAR, listDetail = false, compactHeight = true, cover = true))
        waitForText("Tomato soup")
        compose.onNodeWithText("Tomato soup").performClick()
        waitForTag(RecipeDetailTestTags.COOK)

        // The sections start folded: only their headings show.
        compose.onNodeWithTag(RecipeDetailTestTags.STEPS).assertExists()
        compose.onAllNodesWithTag(RecipeDetailTestTags.STEP).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithTag(RecipeDetailTestTags.STEPS).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(RecipeDetailTestTags.STEP).fetchSemanticsNodes().size == 3 }

        compose.onNodeWithTag(RecipeDetailTestTags.COOK).performClick()
        waitForTag(CookTestTags.INDICATOR)
        assertStep(1, "Chop the tomatoes.")
        compose.onNodeWithTag(CookTestTags.CLOSE).performClick()
        waitForTag(RecipeDetailTestTags.COOK)
    }
}
