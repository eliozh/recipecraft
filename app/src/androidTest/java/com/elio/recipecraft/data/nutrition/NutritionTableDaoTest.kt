package com.elio.recipecraft.data.nutrition

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real Room DAO on an in-memory [AppDatabase]. */
@RunWith(AndroidJUnit4::class)
class NutritionTableDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: NutritionTableDao

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.nutritionTables()
    }

    @After
    fun close() = database.close()

    private fun food(name: String, kcal: Double = 100.0) = NutritionFood(name, emptyList(), kcal)

    @Test
    fun tablesListInPriorityOrderWithFoodCounts() = runBlocking<Unit> {
        val a = dao.insertTableWithFoods("a", 1, listOf(food("鸡蛋"), food("番茄")))
        val b = dao.insertTableWithFoods("b", 2, listOf(food("米饭")))
        val c = dao.insertTableWithFoods("c", 3, listOf(food("豆腐"), food("牛奶"), food("菠菜")))

        assertEquals(
            listOf(
                NutritionTableSummary(a, "a", 0, 1, 2),
                NutritionTableSummary(b, "b", 1, 2, 1),
                NutritionTableSummary(c, "c", 2, 3, 3),
            ),
            dao.observeTables().first(),
        )

        dao.setOrder(listOf(c, a, b))
        assertEquals(listOf("c" to 0, "a" to 1, "b" to 2), dao.observeTables().first().map { it.name to it.priority })
        assertEquals(listOf(c, a, b), dao.tableIdsInOrder())
    }

    @Test
    fun deletingATableCascadesToItsFoods() = runBlocking<Unit> {
        val a = dao.insertTableWithFoods("a", 1, listOf(food("鸡蛋"), food("番茄")))
        val b = dao.insertTableWithFoods("b", 1, listOf(food("米饭")))

        dao.deleteTable(a)
        assertTrue(dao.foods(a).isEmpty())
        assertEquals(listOf(NutritionFoodEntity(b, "米饭", "", 100.0)), dao.foods(b))
        assertEquals(listOf("b" to 1), dao.observeTables().first().map { it.name to it.foodCount })
    }

    @Test
    fun aFailedInsertLeavesNoHalfTable() = runBlocking<Unit> {
        try {
            dao.insertTableWithFoods("dup", 1, listOf(food("鸡蛋"), food("鸡蛋")))
            fail("expected a constraint failure")
        } catch (_: SQLiteConstraintException) {
        }
        assertTrue(dao.observeTables().first().isEmpty())
    }

    @Test
    fun aliasesRoundTrip() = runBlocking<Unit> {
        val id = dao.insertTableWithFoods("a", 1, listOf(NutritionFood("鸡蛋", listOf("鸡子", "蛋"), 144.0)))
        assertEquals(NutritionFood("鸡蛋", listOf("鸡子", "蛋"), 144.0), dao.foods(id).single().toFood())
    }

    @Test
    fun theChineseTableTurnsTomatoAndEggIntoADatabaseTotalUntilItIsDeleted() = runBlocking<Unit> {
        val repository = NutritionTableRepository(dao, CsvTableImporter(), Clock.systemUTC(), Dispatchers.IO)
        val lookups = ImportedNutritionLookups(dao, Dispatchers.IO)
        val reply = javaClass.getResourceAsStream("/fixtures/replies/zh_tomato_egg.txt")!!.bufferedReader().readText()
        val batch = (RecipeParser().parse(reply) as RecipeParseResult.Success).batch
        fun calories() = runBlocking { RecipeAssembler(Clock.systemUTC()).assemble(batch, Language.ZH, lookups.current()).recipes.single().calories }

        assertEquals(CalorieSource.AI_ESTIMATE, calories().source)
        val imported = repository.import("中国食物成分表") {
            javaClass.getResourceAsStream("/fixtures/nutrition/zh_table.csv")!!
        } as TableImportResult.Imported
        assertEquals(CalorieSource.DATABASE, calories().source)
        assertEquals(396, calories().total)

        repository.delete(imported.tableId)
        assertEquals(CalorieSource.AI_ESTIMATE, calories().source)
    }
}
