package com.elio.recipecraft.ui.library

import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.testRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val LibraryViewModel.ids get() = uiState.value.recipes.map { it.id }

    @Test
    fun `an empty library says so`() = runTest {
        val state = LibraryViewModel(repository).uiState.value
        assertFalse(state.loading)
        assertTrue(state.libraryEmpty)
        assertTrue(state.recipes.isEmpty())
    }

    @Test
    fun `saved recipes list newest first and new saves show up`() = runTest {
        repository.save(listOf(testRecipe("old", createdAt = 1)))
        val vm = LibraryViewModel(repository)
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
        val vm = LibraryViewModel(repository)

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
        val state = LibraryViewModel(repository).uiState.value
        assertTrue(state.loadFailed)
        assertFalse(state.loading)
    }
}
