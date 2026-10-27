package com.elio.recipecraft.ui.recipe

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
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
}
