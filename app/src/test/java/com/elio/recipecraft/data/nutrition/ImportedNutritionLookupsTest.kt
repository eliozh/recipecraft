package com.elio.recipecraft.data.nutrition

import app.cash.turbine.test
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.testing.FakeNutritionTableDao
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportedNutritionLookupsTest {

    private val dao = FakeNutritionTableDao()

    private fun TestScope.lookups() = ImportedNutritionLookups(dao, UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.repository() = NutritionTableRepository(
        dao,
        CsvTableImporter(),
        Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC),
        UnconfinedTestDispatcher(testScheduler),
    )

    private suspend fun table(name: String, vararg foods: NutritionFood): Long =
        dao.insertTableWithFoods(name, importedAt = 1, foods = foods.toList())

    private fun ingredient(name: String, nameEn: String) = Ingredient(name, nameEn, "", "", 100.0, null)

    private fun List<NutritionLookup>.kcal(ingredient: Ingredient, language: Language) =
        firstNotNullOfOrNull { it.kcalPer100g(ingredient, language) }

    @Test
    fun `zh recipes match the chinese display name and en recipes match nameEn`() = runTest {
        table("mine", NutritionFood("鸡蛋", listOf("蛋"), 144.0), NutritionFood("egg", emptyList(), 140.0))
        val tables = lookups().current()

        assertEquals(144.0, tables.kcal(ingredient("鸡蛋", "egg"), Language.ZH)!!, 0.0)
        assertEquals(144.0, tables.kcal(ingredient("蛋", "egg"), Language.ZH)!!, 0.0)
        assertEquals(140.0, tables.kcal(ingredient("鸡蛋", "egg"), Language.EN)!!, 0.0)
        // A zh recipe never falls back to nameEn in an imported table, and an en one never to the display name.
        assertNull(tables.kcal(ingredient("土鸡蛋", "egg"), Language.ZH))
        assertNull(tables.kcal(ingredient("鸡蛋", "eggs benedict"), Language.EN))
    }

    @Test
    fun `importedTableKey picks the key by language`() {
        val egg = ingredient("鸡蛋", "egg")
        assertEquals("鸡蛋", importedTableKey(egg, Language.ZH))
        assertEquals("egg", importedTableKey(egg, Language.EN))
    }

    @Test
    fun `tables come in priority order and the first table wins`() = runTest {
        val first = table("first", NutritionFood("鸡蛋", emptyList(), 150.0))
        val second = table("second", NutritionFood("鸡蛋", emptyList(), 130.0), NutritionFood("番茄", emptyList(), 18.0))
        val lookups = lookups()
        val egg = ingredient("鸡蛋", "egg")

        assertEquals(150.0, lookups.current().kcal(egg, Language.ZH)!!, 0.0)
        assertEquals(18.0, lookups.current().kcal(ingredient("番茄", "tomato"), Language.ZH)!!, 0.0)

        dao.setOrder(listOf(second, first))
        assertEquals(130.0, lookups.current().kcal(egg, Language.ZH)!!, 0.0)
    }

    @Test
    fun `each table is read once and dropped from the cache when deleted`() = runTest {
        val id = table("mine", NutritionFood("鸡蛋", emptyList(), 144.0))
        val lookups = lookups()
        lookups.current()
        lookups.current()
        assertEquals(1, dao.foodReads)

        dao.deleteTable(id)
        assertTrue(lookups.current().isEmpty())
        table("again", NutritionFood("鸡蛋", emptyList(), 100.0))
        assertEquals(100.0, lookups.current().kcal(ingredient("鸡蛋", "egg"), Language.ZH)!!, 0.0)
        assertEquals(2, dao.foodReads)
    }

    @Test
    fun `observe emits again when a table is imported or deleted`() = runTest {
        val lookups = lookups()
        lookups.observe().test {
            assertEquals(0, awaitItem().size)
            val id = table("mine", NutritionFood("鸡蛋", emptyList(), 144.0))
            assertEquals(1, awaitItem().size)
            dao.deleteTable(id)
            assertEquals(0, awaitItem().size)
        }
    }

    @Test
    fun `an unreadable database gives no imported tables`() = runTest {
        dao.failReads = true
        assertTrue(lookups().current().isEmpty())
    }

    @Test
    fun `imported tables go before usda`() = runTest {
        table("mine", NutritionFood("egg", emptyList(), 150.0))
        val usdaDao = object : UsdaFoodDao {
            override suspend fun all() = listOf(UsdaFoodEntity("egg", "", 143.0, 1), UsdaFoodEntity("tomato", "", 18.0, 2))
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val combined = CombinedNutritionLookups(lookups(), UsdaNutritionLookups(usdaDao, dispatcher))

        val current = combined.current()
        assertEquals(2, current.size)
        assertEquals(150.0, current.kcal(ingredient("鸡蛋", "egg"), Language.EN)!!, 0.0)
        assertEquals(18.0, current.kcal(ingredient("番茄", "tomato"), Language.EN)!!, 0.0)
        combined.observe().test {
            assertEquals(2, awaitItem().size)
        }
    }

    private fun tomatoEgg(): Recipe {
        val reply = javaClass.getResourceAsStream("/fixtures/replies/zh_tomato_egg.txt")!!.bufferedReader().readText()
        val batch = (RecipeParser().parse(reply) as RecipeParseResult.Success).batch
        return RecipeAssembler(Clock.systemUTC()).assemble(batch, Language.ZH).recipes.single()
    }

    @Test
    fun `importing the chinese csv gives tomato and egg a database total, and deleting it brings back the ai estimate`() = runTest {
        val repository = repository()
        val lookups = lookups()
        val recipe = tomatoEgg()
        assertEquals(Calories(420, 210, CalorieSource.AI_ESTIMATE, 420), CalorieCalculator.calculate(recipe, lookups.current()))

        val imported = repository.import("中国食物成分表") {
            javaClass.getResourceAsStream("/fixtures/nutrition/zh_table.csv")!!
        } as TableImportResult.Imported
        assertEquals(4, imported.imported)

        // 300 g × 15 + 150 g × 144 + 15 g × 899 + 2 g × 0, per 100 g = 395.85
        assertEquals(Calories(396, 198, CalorieSource.DATABASE, 420), CalorieCalculator.calculate(recipe, lookups.current()))

        repository.delete(imported.tableId)
        assertEquals(Calories(420, 210, CalorieSource.AI_ESTIMATE, 420), CalorieCalculator.calculate(recipe, lookups.current()))
    }
}
