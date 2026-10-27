package com.elio.recipecraft.ui.recipe

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.elio.recipecraft.data.llm.AssetReader
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.MutableClock
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
import com.elio.recipecraft.testing.translatedReply
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import com.elio.recipecraft.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private val dao = FakeRecipeDao()
    private val nutrition = FakeNutritionLookups(listOf(lookupOf("egg" to 155.0)))
    private val repository = RecipeRepository(dao, nutrition, UnconfinedTestDispatcher())
    private val undoDelete = UndoDelete()
    private val language = FakeLanguageSettings()
    private val client = FakeLlmClient()
    private val translations = RecipeTranslations(
        FakeLlmClientFactory(LlmClientResult.Ready(client)),
        RecipeTranslator(
            PromptBuilder(AssetReader { File("src/main/assets/$it").readText() }, language, UnconfinedTestDispatcher()),
            RecipeParser(),
            Clock.systemUTC(),
        ),
        repository,
        CoroutineScope(UnconfinedTestDispatcher()),
    )

    private val clock = MutableClock(Instant.ofEpochMilli(50_000))
    private val factory = FakeLlmClientFactory(LlmClientResult.Ready(client))
    private val modifier = RecipeModifier(
        factory,
        PromptBuilder(AssetReader { File("src/main/assets/$it").readText() }, language, UnconfinedTestDispatcher()),
        RecipeParser(),
        RecipeAssembler(clock) { "model-id" },
        nutrition,
        clock,
    )

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private fun reply(text: String) {
        client.events.trySend(LlmEvent.Delta(text))
        client.events.trySend(LlmEvent.Done(null))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String?) = RecipeDetailViewModel(
        SavedStateHandle(if (id == null) emptyMap() else mapOf(RecipeDetailViewModel.ARG_RECIPE_ID to id)),
        repository,
        undoDelete,
        language,
        translations,
        modifier,
        clock,
    )

    private suspend fun saveEggs() {
        repository.save(listOf(testRecipe("a", ingredients = listOf(ingredient("egg", grams = 50.0), ingredient("caviar")))))
    }

    @Test
    fun `loads the recipe with each ingredient's database kcal`() = runTest {
        saveEggs()
        val state = viewModel("a").uiState.value
        assertFalse(state.loading)
        assertEquals("a", state.recipe?.id)
        assertEquals(listOf(78, null), state.ingredientKcal)
        assertFalse(state.notFound)
    }

    @Test
    fun `an id that is not saved shows not found`() = runTest {
        val state = viewModel("nope").uiState.value
        assertFalse(state.loading)
        assertTrue(state.notFound)
        assertNull(state.recipe)
    }

    @Test
    fun `a missing id argument shows not found`() = runTest {
        val vm = viewModel(null)
        assertTrue(vm.uiState.value.notFound)
        vm.delete() // Nothing to delete.
        assertNull(undoDelete.pending.value)
    }

    @Test
    fun `an unreadable database shows a load failure`() = runTest {
        dao.failReads = true
        val state = viewModel("a").uiState.value
        assertTrue(state.loadFailed)
        assertFalse(state.loading)
    }

    @Test
    fun `delete removes the recipe, offers undo and tells the screen to leave`() = runTest {
        saveEggs()
        val saved = dao.recipes.single().toDomain()
        val vm = viewModel("a")

        vm.events.test {
            vm.delete()
            assertEquals(RecipeDetailEvent.Deleted, awaitItem())
        }
        assertTrue(dao.recipes.isEmpty())
        assertEquals(saved, undoDelete.pending.value)
        // The recipe stays on screen while it closes, instead of flashing "not found".
        assertEquals(saved, vm.uiState.value.recipe)
        assertFalse(vm.uiState.value.notFound)
    }

    @Test
    fun `undo from the library restores the identical record`() = runTest {
        saveEggs()
        val before = dao.recipes.single()
        viewModel("a").delete()
        val library = LibraryViewModel(repository, undoDelete)
        assertTrue(library.uiState.value.libraryEmpty)

        library.onUndo(library.pendingUndo.value!!)

        assertEquals(listOf(before), dao.recipes)
        assertEquals(listOf("a"), library.uiState.value.recipes.map { it.id })
        assertNull(undoDelete.pending.value)
    }

    @Test
    fun `a failed delete keeps the recipe and reports it`() = runTest {
        saveEggs()
        val vm = viewModel("a")
        dao.failWrites = true

        vm.delete()

        assertEquals(1, dao.recipes.size)
        assertTrue(vm.uiState.value.deleteFailed)
        assertFalse(vm.uiState.value.deleting)
        assertNull(undoDelete.pending.value)
        vm.onDeleteFailedShown()
        assertFalse(vm.uiState.value.deleteFailed)
    }

    @Test
    fun `a recipe deleted elsewhere shows not found`() = runTest {
        saveEggs()
        val vm = viewModel("a")
        repository.delete("a")
        assertTrue(vm.uiState.value.notFound)
        assertNull(vm.uiState.value.recipe)
    }

    @Test
    fun `a recipe in another language offers Translate and is translated in place`() = runTest {
        saveEggs()
        val original = dao.recipes.single().toDomain()
        language.resolved = Language.ZH
        val vm = viewModel("a")
        assertTrue(vm.uiState.value.canTranslate)
        client.events.trySend(LlmEvent.Delta(translatedReply(original)))
        client.events.trySend(LlmEvent.Done(null))

        vm.translate()

        val translated = vm.uiState.value.recipe!!
        assertEquals(Language.ZH, translated.language)
        assertEquals("译 ${original.name}", translated.name)
        assertEquals(original.id, translated.id)
        assertEquals(original.calories, translated.calories)
        assertFalse(vm.uiState.value.translating)
        assertNull(vm.uiState.value.translateError)
        assertFalse(vm.uiState.value.canTranslate)
    }

    @Test
    fun `a recipe in the app language has no Translate until the language changes`() = runTest {
        saveEggs()
        val vm = viewModel("a")
        assertFalse(vm.uiState.value.canTranslate)
        vm.translate()
        assertTrue(client.requests.isEmpty())

        language.resolved = Language.ZH
        vm.onResume()
        assertTrue(vm.uiState.value.canTranslate)
    }

    @Test
    fun `a failed translation is reported and the recipe stays as it was`() = runTest {
        saveEggs()
        val before = dao.recipes.single()
        language.resolved = Language.ZH
        val vm = viewModel("a")
        client.events.trySend(LlmEvent.Error(LlmErrorKind.InvalidKey))

        vm.translate()

        assertEquals(TranslationError.Llm(LlmErrorKind.InvalidKey), vm.uiState.value.translateError)
        assertEquals(listOf(before), dao.recipes)
        assertTrue(vm.uiState.value.canTranslate)
        vm.onTranslateErrorShown()
        assertNull(vm.uiState.value.translateError)
    }

    /** Saves "a" in a set, opens it and gets the vegetarian change into the preview. */
    private suspend fun previewVegetarian(): RecipeDetailViewModel {
        repository.save(listOf(testRecipe("a", setId = "set-1", createdAt = 1_000, ingredients = listOf(ingredient("egg", grams = 50.0)))))
        val vm = viewModel("a")
        assertTrue(vm.uiState.value.canModify)
        vm.openModify()
        assertEquals(DetailModifyState(), vm.uiState.value.modify)
        assertFalse(vm.uiState.value.canTranslate)
        vm.onModifyInstructionChanged("Make it vegetarian")
        reply(fixture("modify_en_lunch_vegetarian.txt"))
        vm.sendModify()
        return vm
    }

    @Test
    fun `a successful change shows the preview and writes nothing yet`() = runTest {
        val vm = previewVegetarian()
        val before = dao.recipes.single()

        val modify = vm.uiState.value.modify!!
        assertFalse(modify.running)
        assertNull(modify.error)
        val preview = modify.preview!!
        assertEquals(before.toDomain(), preview.before)
        assertEquals("Chickpea salad wrap", preview.after.name)
        assertEquals("a", preview.after.id)
        assertTrue(client.requests.single().messages.last().content.contains("Make it vegetarian"))
        assertEquals(listOf(before), dao.recipes)
        assertFalse(modify.canSend)
        vm.sendModify() // already previewing
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `replace updates the saved recipe in place with a new update time`() = runTest {
        val vm = previewVegetarian()

        vm.events.test {
            vm.replace()
            assertEquals(RecipeDetailEvent.Replaced, awaitItem())
        }

        val saved = dao.recipes.single().toDomain()
        assertEquals("a", saved.id)
        assertEquals("Chickpea salad wrap", saved.name)
        assertEquals("set-1", saved.setId)
        assertEquals(1_000L, saved.createdAt)
        assertEquals(50_000L, saved.updatedAt)
        assertNull(vm.uiState.value.modify)
        assertEquals(saved, vm.uiState.value.recipe)
    }

    @Test
    fun `save as copy adds a new record and leaves the original as it was`() = runTest {
        val vm = previewVegetarian()
        val original = dao.recipes.single()
        clock.instant = Instant.ofEpochMilli(60_000)

        vm.events.test {
            vm.saveAsCopy()
            val event = awaitItem() as RecipeDetailEvent.SavedCopy
            assertNotEquals("a", event.id)
            val copy = dao.recipes.single { it.id == event.id }.toDomain()
            assertEquals("Chickpea salad wrap", copy.name)
            assertNull(copy.setId)
            assertEquals(60_000L, copy.createdAt)
            assertEquals(60_000L, copy.updatedAt)
        }

        assertEquals(2, dao.recipes.size)
        assertEquals(original, dao.recipes.single { it.id == "a" })
        assertNull(vm.uiState.value.modify)
        assertEquals(original.toDomain(), vm.uiState.value.recipe)
    }

    @Test
    fun `cancelling the preview leaves the original as it was`() = runTest {
        val vm = previewVegetarian()
        val before = dao.recipes.toList()

        vm.cancelModify()

        assertNull(vm.uiState.value.modify)
        assertEquals(before, dao.recipes)
        vm.replace() // nothing to replace
        vm.saveAsCopy()
        assertEquals(before, dao.recipes)
    }

    @Test
    fun `a failure keeps the sheet open with the error and the original as it was`() = runTest {
        saveEggs()
        val before = dao.recipes.toList()
        val vm = viewModel("a")
        vm.openModify()
        vm.onModifyInstructionChanged("Vegetarian")
        client.events.trySend(LlmEvent.Error(LlmErrorKind.InvalidKey))

        vm.sendModify()

        assertEquals(
            DetailModifyState("Vegetarian", running = false, error = ModifyError.Llm(LlmErrorKind.InvalidKey)),
            vm.uiState.value.modify,
        )
        assertEquals(before, dao.recipes)

        // No key at all: the same sheet reports it, and retrying clears the old error first.
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        vm.sendModify()
        assertEquals(ModifyError.Llm(LlmErrorKind.MissingKey), vm.uiState.value.modify?.error)

        factory.result = LlmClientResult.Ready(client)
        reply("not a recipe")
        reply("still not a recipe")
        vm.sendModify()
        assertEquals(ModifyError.Rejected, vm.uiState.value.modify?.error)
        assertEquals(before, dao.recipes)
    }

    @Test
    fun `cancel stops a running change and its late reply changes nothing`() = runTest {
        saveEggs()
        val vm = viewModel("a")
        vm.openModify()
        vm.onModifyInstructionChanged("Vegetarian")
        vm.sendModify()
        assertTrue(vm.uiState.value.modify!!.running)
        vm.onModifyInstructionChanged("ignored while running")
        assertEquals("Vegetarian", vm.uiState.value.modify?.instruction)
        vm.openModify() // already open

        vm.cancelModify()

        assertNull(vm.uiState.value.modify)
        assertTrue(client.cancelled)
        assertTrue(vm.uiState.value.canModify)
    }

    @Test
    fun `a blank instruction or a closed sheet sends nothing`() = runTest {
        saveEggs()
        val vm = viewModel("a")
        vm.sendModify()
        vm.onModifyInstructionChanged("ignored without a sheet")
        assertNull(vm.uiState.value.modify)
        vm.openModify()
        vm.onModifyInstructionChanged("   ")
        vm.sendModify()
        assertTrue(client.requests.isEmpty())
        assertFalse(viewModel("nope").uiState.value.canModify)
    }

    @Test
    fun `a failed write keeps the preview open to try again`() = runTest {
        val vm = previewVegetarian()
        val before = dao.recipes.toList()
        dao.failWrites = true

        vm.replace()
        assertTrue(vm.uiState.value.modifySaveFailed)
        assertNotNull(vm.uiState.value.modify?.preview)
        assertFalse(vm.uiState.value.modify!!.saving)
        vm.saveAsCopy()
        assertTrue(vm.uiState.value.modifySaveFailed)
        assertEquals(before, dao.recipes)
        vm.onModifySaveFailedShown()
        assertFalse(vm.uiState.value.modifySaveFailed)

        dao.failWrites = false
        vm.replace()
        assertEquals("Chickpea salad wrap", dao.recipes.single().name)
    }

    @Test
    fun `replacing a recipe deleted meanwhile closes the preview and adds nothing`() = runTest {
        val vm = previewVegetarian()
        repository.delete("a")

        vm.events.test {
            vm.replace()
            expectNoEvents()
        }
        assertTrue(dao.recipes.isEmpty())
        assertNull(vm.uiState.value.modify)
        assertTrue(vm.uiState.value.notFound)
    }
}
