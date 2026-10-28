package com.elio.recipecraft.ui.library

import app.cash.turbine.test
import com.elio.recipecraft.data.llm.AssetReader
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.recipe.LanguageSwitchCheck
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.recipe.TranslationBatchState
import com.elio.recipecraft.data.recipe.TranslationOffer
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.InMemoryDataStore
import com.elio.recipecraft.testing.testRecipe
import com.elio.recipecraft.testing.translatedReply
import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {

    private val dao = FakeRecipeDao()
    private val repository = RecipeRepository(dao, FakeNutritionLookups(), UnconfinedTestDispatcher())
    private val dataStore = InMemoryDataStore()
    private val settings = SettingsRepository(dataStore)
    private val language = FakeLanguageSettings()
    private val client = FakeLlmClient()
    private val english = (1..3).map { testRecipe("en-$it", createdAt = 10L - it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = TranslationViewModel(
        LanguageSwitchCheck(settings, language, repository),
        RecipeTranslations(
            FakeLlmClientFactory(LlmClientResult.Ready(client)),
            RecipeTranslator(
                PromptBuilder(AssetReader { File("src/main/assets/$it").readText() }, language, UnconfinedTestDispatcher()),
                RecipeParser(),
                Clock.systemUTC(),
            ),
            repository,
            backgroundScope,
        ),
    )

    private suspend fun switchToChineseWithEnglishRecipes() {
        repository.save(english)
        settings.setLastLanguage(Language.EN)
        language.resolved = Language.ZH
    }

    @Test
    fun `resuming after a switch offers the recipes in the other language`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()

        vm.onResume()
        vm.onResume() // A second resume, such as after rotation, keeps the same offer.

        assertEquals(TranslationOffer(3, Language.EN, Language.ZH), vm.uiState.value.offer)
    }

    @Test
    fun `Translate stores the language and runs the batch to the end`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()
        vm.onResume()

        vm.uiState.test {
            assertEquals(TranslationBatchState.Idle, awaitItem().batch)
            vm.onTranslate()
            var state = awaitItem()
            while (state.batch != TranslationBatchState.Running(1, 3)) state = awaitItem()
            assertNull(state.offer)
            english.forEach { reply(translatedReply(it)) }
            while (state.batch !is TranslationBatchState.Finished) state = awaitItem()
            assertEquals(TranslationBatchState.Finished(3, 0, cancelled = false, error = null), state.batch)
            vm.onResultShown()
            assertEquals(TranslationBatchState.Idle, awaitItem().batch)
        }

        assertEquals(Language.ZH, settings.lastLanguage.first())
        assertEquals(listOf(Language.ZH), dao.recipes.map { it.language }.distinct())
        vm.onResume()
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun `Cancel stops the batch after the recipes already translated`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()
        vm.onResume()
        vm.onTranslate()
        reply(translatedReply(english[0]))

        vm.onCancel()

        assertEquals(TranslationBatchState.Finished(1, 0, cancelled = true, error = null), vm.uiState.value.batch)
        assertEquals(listOf(Language.ZH, Language.EN, Language.EN), dao.recipes.map { it.language })
    }

    @Test
    fun `Not now leaves the recipes in English and does not ask again`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()
        vm.onResume()

        vm.onNotNow()

        assertNull(vm.uiState.value.offer)
        assertEquals(TranslationBatchState.Idle, vm.uiState.value.batch)
        assertEquals(english, dao.recipes.map { it.toDomain() })
        assertEquals(Language.ZH, settings.lastLanguage.first())
        vm.onResume()
        assertNull(vm.uiState.value.offer)
        vm.onTranslate() // Nothing to answer any more.
        assertEquals(TranslationBatchState.Idle, vm.uiState.value.batch)
    }

    @Test
    fun `an unreadable database offers nothing and tries again on the next resume`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()
        dao.failReads = true
        vm.onResume()
        assertNull(vm.uiState.value.offer)

        dao.failReads = false
        vm.onResume()
        assertEquals(3, vm.uiState.value.offer?.count)
    }

    @Test
    fun `switching back while the offer is shown closes it, and switching again offers it again`() = runTest(UnconfinedTestDispatcher()) {
        switchToChineseWithEnglishRecipes()
        val vm = viewModel()
        vm.onResume()
        assertEquals(TranslationOffer(3, Language.EN, Language.ZH), vm.uiState.value.offer)

        language.resolved = Language.EN
        vm.onResume()
        assertNull(vm.uiState.value.offer)
        vm.onTranslate() // Nothing to answer.
        assertEquals(TranslationBatchState.Idle, vm.uiState.value.batch)

        language.resolved = Language.ZH
        vm.onResume()
        assertEquals(TranslationOffer(3, Language.EN, Language.ZH), vm.uiState.value.offer)
    }

    private fun reply(text: String) {
        client.events.trySend(LlmEvent.Delta(text))
        client.events.trySend(LlmEvent.Done(null))
    }
}
