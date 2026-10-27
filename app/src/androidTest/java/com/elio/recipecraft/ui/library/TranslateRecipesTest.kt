package com.elio.recipecraft.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.data.recipe.LanguageSwitchCheck
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.ui.recipe.RecipeDetailScreen
import com.elio.recipecraft.ui.recipe.RecipeDetailTestTags
import com.elio.recipecraft.ui.recipe.RecipeDetailViewModel
import com.elio.recipecraft.ui.recipe.UndoDelete
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app was in English, three English recipes are saved, and the app is now in Chinese: the offer,
 * Translate with its progress and result, Cancel, and Not now followed by the detail screen's
 * Translate. The model is scripted; each reply waits until the test sends it.
 */
@RunWith(AndroidJUnit4::class)
class TranslateRecipesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsFile = File(context.cacheDir, "translate-test-${System.nanoTime()}.preferences_pb")
    private val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })

    private val chinese = object : AppLanguageSettings {
        override val choice = AppLanguage.ZH
        override val resolvedLanguage = Language.ZH
        override fun setChoice(choice: AppLanguage) = Unit
    }

    /** USDA-like: matches on nameEn, which translation keeps, so calories stay the same. */
    private val repository = RecipeRepository(
        database.recipes(),
        object : NutritionLookups {
            private val lookups = listOf<NutritionLookup>(TableLookup(IngredientMatcher(listOf(NutritionFood("egg", emptyList(), 143.0)))))
            override suspend fun current() = lookups
            override fun observe() = flowOf(lookups)
        },
        Dispatchers.IO,
    )

    private val replies = Channel<String>(Channel.UNLIMITED)
    private val client = object : LlmClient {
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            emit(LlmEvent.Delta(replies.receive()))
            emit(LlmEvent.Done(null))
        }

        override suspend fun listModels() = ModelListResult.Success(emptyList())
    }
    private val translations = RecipeTranslations(
        object : LlmClientFactory {
            override suspend fun forActiveProvider(requireModel: Boolean) = LlmClientResult.Ready(client)
            override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = forActiveProvider(requireModel)
        },
        RecipeTranslator(PromptBuilder(AndroidAssetReader(context), chinese, Dispatchers.IO), RecipeParser(), Clock.systemUTC()),
        repository,
        scope,
    )

    /** Newest first, so the batch takes them in this order. */
    private val english = listOf("Fried eggs", "Egg salad", "Boiled eggs").mapIndexed { i, name ->
        Recipe(
            id = "r$i",
            name = name,
            mealType = MealType.BREAKFAST,
            servings = 2,
            calories = Calories(300, 150, CalorieSource.AI_ESTIMATE, 300),
            ingredients = listOf(Ingredient("Eggs", "egg", "2", "", 100.0, 140), Ingredient("Salt", "salt", "", "a pinch", null, 0)),
            tools = listOf("Pan"),
            steps = listOf(Step(1, "Cook the eggs.", 5), Step(2, "Season.", null)),
            language = Language.EN,
            setId = "set-1",
            createdAt = 3_000L - i,
            updatedAt = 3_000L - i,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        settingsFile.delete()
    }

    private fun saveEnglishAfterAnEnglishSession(): List<Recipe> = runBlocking {
        repository.save(english)
        settings.setLastLanguage(Language.EN)
        database.recipes().all().map { it.toDomain() }.sortedByDescending { it.createdAt }
    }

    private fun setContent(detailId: String? = null) {
        val translationViewModel = TranslationViewModel(LanguageSwitchCheck(settings, chinese, repository), translations)
        compose.setContent {
            RecipeCraftTheme {
                Box {
                    if (detailId != null) {
                        RecipeDetailScreen(
                            onBack = {},
                            onDeleted = {},
                            viewModel = viewModel {
                                RecipeDetailViewModel(
                                    SavedStateHandle(mapOf(RecipeDetailViewModel.ARG_RECIPE_ID to detailId)),
                                    repository,
                                    UndoDelete(),
                                    chinese,
                                    translations,
                                )
                            },
                        )
                    }
                    TranslationHost(translationViewModel)
                }
            }
        }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForProgress(current: Int) {
        val text = context.getString(R.string.translate_progress, current, english.size)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(TranslationTestTags.PROGRESS_TEXT) and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saved() = runBlocking { database.recipes().all().map { it.toDomain() }.associateBy { it.id } }

    @Test
    fun translateConvertsEveryRecipeAndKeepsIdsSetsGramsAndCalories() {
        val before = saveEnglishAfterAnEnglishSession()
        setContent()

        waitForTag(TranslationTestTags.OFFER)
        compose.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.translate_offer_message,
                3,
                3,
                context.getString(R.string.language_english),
                context.getString(R.string.language_chinese),
            ),
        ).assertExists()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).performClick()

        before.forEachIndexed { i, recipe ->
            waitForProgress(i + 1)
            replies.trySend(translatedReply(recipe))
        }
        waitForTag(TranslationTestTags.RESULT)
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT, useUnmergedTree = true)
            .assertTextEquals(context.resources.getQuantityString(R.plurals.translate_done_translated, 3, 3))
        compose.onNodeWithTag(TranslationTestTags.OK).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TranslationTestTags.RESULT).fetchSemanticsNodes().isEmpty() }

        val after = saved()
        before.forEach { original ->
            val translated = after.getValue(original.id)
            assertEquals(Language.ZH, translated.language)
            assertEquals("译 ${original.name}", translated.name)
            assertEquals(original.setId, translated.setId)
            assertEquals(original.ingredients.map { it.grams }, translated.ingredients.map { it.grams })
            assertEquals(original.calories, translated.calories)
        }
        assertEquals(CalorieSource.DATABASE, after.values.first().calories.source)
        assertEquals(Language.ZH, runBlocking { settings.lastLanguage.first() })
    }

    @Test
    fun cancelKeepsTheTranslatedRecipesAndLeavesTheRest() {
        val before = saveEnglishAfterAnEnglishSession()
        setContent()

        waitForTag(TranslationTestTags.OFFER)
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).performClick()
        waitForProgress(1)
        replies.trySend(translatedReply(before[0]))
        waitForProgress(2)
        compose.onNodeWithTag(TranslationTestTags.CANCEL).performClick()

        waitForTag(TranslationTestTags.RESULT)
        compose.onNodeWithText(context.getString(R.string.translate_cancelled_title)).assertExists()
        val after = saved()
        assertEquals(Language.ZH, after.getValue(before[0].id).language)
        assertEquals(before.drop(1), before.drop(1).map { after.getValue(it.id) })
    }

    @Test
    fun notNowLeavesTheRecipesInEnglishAndTheDetailActionStillTranslates() {
        val before = saveEnglishAfterAnEnglishSession()
        setContent(detailId = before[1].id)

        waitForTag(TranslationTestTags.OFFER)
        compose.onNodeWithTag(TranslationTestTags.NOT_NOW).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TranslationTestTags.OFFER).fetchSemanticsNodes().isEmpty() }
        assertEquals(before.associateBy { it.id }, saved())
        assertEquals(Language.ZH, runBlocking { settings.lastLanguage.first() })

        waitForTag(RecipeDetailTestTags.TRANSLATE)
        compose.onNodeWithTag(RecipeDetailTestTags.TRANSLATE).performClick()
        replies.trySend(translatedReply(before[1]))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(RecipeDetailTestTags.NAME) and hasText("译 Egg salad")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(RecipeDetailTestTags.TRANSLATE).assertDoesNotExist()
        val after = saved()
        assertEquals(Language.ZH, after.getValue(before[1].id).language)
        assertEquals(before[1].calories, after.getValue(before[1].id).calories)
        assertEquals(Language.EN, after.getValue(before[0].id).language)
    }

    /** A faithful translation: every text field prefixed, every number and key as it was. */
    private fun translatedReply(recipe: Recipe): String = buildJsonObject {
        put("name", "译 ${recipe.name}")
        put("servings", recipe.servings)
        put(
            "ingredients",
            JsonArray(
                recipe.ingredients.map {
                    buildJsonObject {
                        put("name", "译 ${it.name}")
                        put("nameEn", it.nameEn)
                        put("quantity", it.quantity)
                        put("unit", it.unit)
                        put("grams", it.grams)
                    }
                },
            ),
        )
        put("tools", JsonArray(recipe.tools.map { JsonPrimitive("译 $it") }))
        put(
            "steps",
            JsonArray(
                recipe.steps.map {
                    buildJsonObject {
                        put("text", "译 ${it.text}")
                        put("minutes", it.minutes)
                    }
                },
            ),
        )
    }.toString()
}
