package com.elio.recipecraft.ui.library

import com.elio.recipecraft.data.recipe.ExportImportService
import com.elio.recipecraft.data.recipe.RecipeDocuments
import com.elio.recipecraft.data.recipe.RecipeExportResult
import com.elio.recipecraft.data.recipe.RecipeImportError
import com.elio.recipecraft.data.recipe.RecipeImportResult
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.FakeRecipeDocuments
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.testRecipe
import com.elio.recipecraft.ui.recipe.UndoDelete
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class LibraryViewModelTest {

    private val dao = FakeRecipeDao()
    private val repository = RecipeRepository(dao, FakeNutritionLookups(), UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val documents = FakeRecipeDocuments()

    private fun libraryViewModel(undo: UndoDelete = UndoDelete(), documents: RecipeDocuments = this.documents) =
        LibraryViewModel(repository, undo, ExportImportService(repository, Clock.systemUTC(), UnconfinedTestDispatcher()), documents)

    private val LibraryViewModel.ids get() = uiState.value.recipes.map { it.id }

    @Test
    fun `an empty library says so`() = runTest {
        val state = libraryViewModel(UndoDelete()).uiState.value
        assertFalse(state.loading)
        assertTrue(state.libraryEmpty)
        assertTrue(state.recipes.isEmpty())
    }

    @Test
    fun `saved recipes list newest first and new saves show up`() = runTest {
        repository.save(listOf(testRecipe("old", createdAt = 1)))
        val vm = libraryViewModel(UndoDelete())
        assertEquals(listOf("old"), vm.ids)

        repository.save(listOf(testRecipe("new", createdAt = 2)))
        assertEquals(listOf("new", "old"), vm.ids)
        assertFalse(vm.uiState.value.libraryEmpty)
    }

    @Test
    fun `the meal type filter and the search narrow the list`() = runTest {
        repository.save(
            listOf(
                testRecipe("oats", name = "Oatmeal", mealType = MealType.BREAKFAST, ingredients = listOf(ingredient("rolled oats")), createdAt = 3),
                testRecipe("eggs", name = "番茄炒蛋", mealType = MealType.DINNER, ingredients = listOf(ingredient("鸡蛋", "egg")), createdAt = 2),
                testRecipe("toast", name = "Egg toast", mealType = MealType.BREAKFAST, createdAt = 1),
            ),
        )
        val vm = libraryViewModel(UndoDelete())

        vm.onMealTypeSelected(MealType.BREAKFAST)
        assertEquals(listOf("oats", "toast"), vm.ids)

        vm.onQueryChanged("EGG")
        assertEquals("EGG", vm.uiState.value.query)
        assertEquals(listOf("toast"), vm.ids)

        vm.onMealTypeSelected(null)
        assertEquals(listOf("eggs", "toast"), vm.ids)

        vm.onQueryChanged("鸡蛋")
        assertEquals(listOf("eggs"), vm.ids)

        vm.onQueryChanged("pizza")
        assertTrue(vm.uiState.value.recipes.isEmpty())
        assertFalse(vm.uiState.value.libraryEmpty)
    }

    @Test
    fun `an unreadable database shows a load failure`() = runTest {
        dao.failReads = true
        val state = libraryViewModel(UndoDelete()).uiState.value
        assertTrue(state.loadFailed)
        assertFalse(state.loading)
    }

    @Test
    fun `dismissing the deleted snackbar drops the recipe for good`() = runTest {
        val undo = UndoDelete()
        val recipe = testRecipe("a")
        undo.offer(recipe)
        val vm = libraryViewModel(undo)
        assertEquals(recipe, vm.pendingUndo.value)

        vm.onUndoDismissed(recipe)
        assertNull(vm.pendingUndo.value)
        // A late Undo for the same recipe does nothing.
        vm.onUndo(recipe)
        assertTrue(dao.recipes.isEmpty())
    }

    @Test
    fun `a failed undo reports it once`() = runTest {
        val undo = UndoDelete()
        val recipe = testRecipe("a")
        undo.offer(recipe)
        val vm = libraryViewModel(undo)
        dao.failWrites = true

        vm.onUndo(recipe)
        assertTrue(vm.uiState.value.restoreFailed)
        vm.onRestoreFailedShown()
        assertFalse(vm.uiState.value.restoreFailed)
    }

    @Test
    fun `export all writes the library to the picked file and reports the count once`() = runTest {
        repository.save(listOf(testRecipe("a"), testRecipe("b")))
        val vm = libraryViewModel()

        vm.onExportTo(null) // backed out of the picker
        assertNull(vm.uiState.value.exportResult)

        vm.onExportTo("content://export.json")
        assertFalse(vm.uiState.value.transferring)
        assertEquals(RecipeExportResult.Exported(2), vm.uiState.value.exportResult)
        assertTrue(documents.files.getValue("content://export.json").decodeToString().contains("\"version\": 1"))

        vm.onExportResultShown()
        assertNull(vm.uiState.value.exportResult)
    }

    @Test
    fun `a file that cannot be written reports a failed export`() = runTest {
        repository.save(listOf(testRecipe("a")))
        val vm = libraryViewModel(documents = object : RecipeDocuments by documents {
            override fun openOutput(uri: String) = throw IOException("read-only")
        })
        vm.onExportTo("content://export.json")
        assertEquals(RecipeExportResult.Failed, vm.uiState.value.exportResult)
    }

    @Test
    fun `import adds the recipes of the picked file and shows a summary until dismissed`() = runTest {
        repository.save(listOf(testRecipe("a", createdAt = 2), testRecipe("b", createdAt = 1)))
        val vm = libraryViewModel()
        vm.onExportTo("content://export.json")
        repository.delete("a")
        assertEquals(listOf("b"), vm.ids)

        vm.onImportFrom(null)
        assertNull(vm.uiState.value.importResult)
        vm.onImportFrom("content://export.json")
        assertFalse(vm.uiState.value.transferring)
        assertEquals(RecipeImportResult.Imported(imported = 1, duplicates = 1, invalid = 0), vm.uiState.value.importResult)
        assertEquals(listOf("a", "b"), vm.ids)

        vm.onImportResultDismissed()
        assertNull(vm.uiState.value.importResult)
    }

    @Test
    fun `importing a file that is gone reports it`() = runTest {
        val vm = libraryViewModel()
        vm.onImportFrom("content://missing.json")
        assertEquals(RecipeImportResult.Failed(RecipeImportError.UNREADABLE), vm.uiState.value.importResult)
    }

    @Test
    fun `a second transfer waits for the first to finish`() = runTest {
        repository.save(listOf(testRecipe("a")))
        val slow = ExportImportService(repository, Clock.systemUTC(), StandardTestDispatcher(testScheduler))
        val vm = LibraryViewModel(repository, UndoDelete(), slow, documents)

        vm.onExportTo("content://first.json")
        assertTrue(vm.uiState.value.transferring)
        vm.onImportFrom("content://first.json")
        vm.onExportTo("content://second.json")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.transferring)
        assertEquals(RecipeExportResult.Exported(1), vm.uiState.value.exportResult)
        assertNull(vm.uiState.value.importResult)
        assertEquals(setOf("content://first.json"), documents.files.keys)
    }
}
