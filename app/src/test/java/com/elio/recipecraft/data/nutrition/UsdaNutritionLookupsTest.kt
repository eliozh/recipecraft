package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsdaNutritionLookupsTest {

    private class FakeDao(var rows: List<UsdaFoodEntity>) : UsdaFoodDao {
        var calls = 0
        var fail = false
        override suspend fun all(): List<UsdaFoodEntity> {
            calls++
            if (fail) throw IllegalStateException("Unable to copy database file")
            return rows
        }
    }

    private fun ingredient(nameEn: String) = Ingredient(nameEn, nameEn, "", "", 100.0, null)

    @Test
    fun `rows load once and aliases split on the bar`() = runTest {
        val dao = FakeDao(listOf(UsdaFoodEntity("egg", "eggs | whole egg|", 143.0, 171287)))
        val lookups = UsdaNutritionLookups(dao, UnconfinedTestDispatcher(testScheduler))

        val usda = lookups.current().single()
        assertEquals(143.0, usda.kcalPer100g(ingredient("whole egg"), Language.EN)!!, 0.0)
        assertEquals(143.0, usda.kcalPer100g(ingredient("egg"), Language.ZH)!!, 0.0)
        lookups.current()
        assertEquals(1, dao.calls)
    }

    @Test
    fun `a table that cannot be read gives no lookup and is retried later`() = runTest {
        val dao = FakeDao(emptyList()).apply { fail = true }
        val lookups = UsdaNutritionLookups(dao, UnconfinedTestDispatcher(testScheduler))

        assertTrue(lookups.current().isEmpty())
        dao.fail = false
        assertEquals(1, lookups.current().size)
        assertEquals(2, dao.calls)
    }

    @Test
    fun `observe emits the table once`() = runTest {
        val lookups = UsdaNutritionLookups(FakeDao(listOf(UsdaFoodEntity("egg", "", 143.0, 171287))), UnconfinedTestDispatcher(testScheduler))
        assertEquals(listOf(1), lookups.observe().toList().map { it.size })
    }
}
