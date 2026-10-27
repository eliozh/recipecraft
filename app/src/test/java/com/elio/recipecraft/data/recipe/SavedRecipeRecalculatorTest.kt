package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedRecipeRecalculatorTest {

    private val dao = FakeRecipeDao()
    private val tableChanges = MutableSharedFlow<List<NutritionLookup>>()
    private val nutrition = FakeNutritionLookups().apply { changes = tableChanges }
    private val repository = RecipeRepository(dao, nutrition, UnconfinedTestDispatcher())

    private fun source(id: String) = dao.recipes.first { it.id == id }.calories.source

    @Test
    fun `a table change recalculates saved recipes and writes only the changed ones`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(
            listOf(
                testRecipe("eggs", ingredients = listOf(ingredient("egg"))),
                testRecipe("rice", ingredients = listOf(ingredient("rice"))),
            ),
        )
        SavedRecipeRecalculator(repository, nutrition, backgroundScope).start()

        tableChanges.emit(listOf(lookupOf("egg" to 143.0)))
        assertEquals(CalorieSource.DATABASE, source("eggs"))
        assertEquals(CalorieSource.AI_ESTIMATE, source("rice"))
        assertEquals(listOf("eggs"), dao.calorieWrites)

        tableChanges.emit(listOf(lookupOf("egg" to 143.0), lookupOf("rice" to 130.0)))
        assertEquals(CalorieSource.DATABASE, source("rice"))
        assertEquals(listOf("eggs", "rice"), dao.calorieWrites)
    }

    @Test
    fun `a database failure is skipped and the next table change tries again`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(listOf(testRecipe("eggs", ingredients = listOf(ingredient("egg")))))
        val job = SavedRecipeRecalculator(repository, nutrition, backgroundScope).start()

        dao.failReads = true
        tableChanges.emit(listOf(lookupOf("egg" to 143.0)))
        assertTrue(job.isActive)
        assertEquals(CalorieSource.AI_ESTIMATE, source("eggs"))

        dao.failReads = false
        tableChanges.emit(listOf(lookupOf("egg" to 143.0)))
        assertEquals(CalorieSource.DATABASE, source("eggs"))
    }
}
