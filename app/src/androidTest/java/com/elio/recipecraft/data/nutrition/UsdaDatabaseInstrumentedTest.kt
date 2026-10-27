package com.elio.recipecraft.data.nutrition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the real bundled asset through Room's prepackaged-database path. */
@RunWith(AndroidJUnit4::class)
class UsdaDatabaseInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "usda-test.db"
    private lateinit var database: UsdaDatabase

    @Before
    fun open() {
        context.deleteDatabase(name)
        database = UsdaDatabase.open(context, name)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun ingredient(nameEn: String) = Ingredient(nameEn, nameEn, "", "", 100.0, null)

    @Test
    fun theAssetOpensAndHoldsTheCuratedRows() = runBlocking<Unit> {
        val rows = database.foods().all()
        assertTrue("rows: ${rows.size}", rows.size in 400..700)
        assertTrue(rows.all { it.kcalPer100g >= 0 && it.fdcId > 0 })
    }

    @Test
    fun commonIngredientsMatch() = runBlocking<Unit> {
        val usda = UsdaNutritionLookups(database.foods(), Dispatchers.IO).current().single()
        fun kcal(key: String) = usda.kcalPer100g(ingredient(key), Language.EN)

        assertEquals(143.0, kcal("egg")!!, 0.0)
        assertEquals(143.0, kcal("Eggs")!!, 0.0)
        assertEquals(379.0, kcal("rolled oats")!!, 0.0)
        assertEquals(61.0, kcal("whole milk")!!, 0.0)
        assertNotNull(kcal("tofu"))
        assertNotNull(kcal("soy sauce"))
        assertNotNull(kcal("bok choy"))
        assertNotNull(kcal("scallions"))
        assertEquals(25.0, kcal("eggplant")!!, 0.0)
        assertNull(kcal("egg sandwich"))
    }

    @Test
    fun theOatmealFixtureGetsADatabaseTotal() = runBlocking<Unit> {
        val usda = UsdaNutritionLookups(database.foods(), Dispatchers.IO).current()
        val reply = javaClass.getResourceAsStream("/fixtures/replies/en_breakfast.txt")!!.bufferedReader().readText()
        val batch = (RecipeParser().parse(reply) as RecipeParseResult.Success).batch
        val calories = RecipeAssembler(Clock.systemUTC()).assemble(batch, Language.EN, usda).recipes.single().calories

        // 80 g × 379 + 366 g × 61 + 118 g × 89 + 7 g × 304, per 100 g = 652.76
        assertEquals(CalorieSource.DATABASE, calories.source)
        assertEquals(653, calories.total)
        assertEquals(327, calories.perServing)
    }
}
