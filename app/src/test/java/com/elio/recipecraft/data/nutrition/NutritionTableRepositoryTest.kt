package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.testing.FakeNutritionTableDao
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NutritionTableRepositoryTest {

    private val now = Instant.parse("2026-10-27T08:00:00Z")
    private val dao = FakeNutritionTableDao()

    private fun TestScope.repository() =
        NutritionTableRepository(dao, CsvTableImporter(), Clock.fixed(now, ZoneOffset.UTC), UnconfinedTestDispatcher(testScheduler))

    private fun csv(vararg rows: String) = { (listOf("name,aliases,kcal_per_100g") + rows).joinToString("\n").byteInputStream() }

    private suspend fun NutritionTableRepository.names() = tables.first().map { it.name }

    @Test
    fun `an import stores the good rows after the existing tables and reports the skipped ones`() = runTest {
        val repository = repository()
        repository.import("first", csv("鸡蛋,鸡子|蛋,144"))
        val result = repository.import("second", csv("番茄,,15", ",,1", "米饭,,116"))

        assertEquals(TableImportResult.Imported(2, imported = 2, skipped = 1, problems = listOf(RowProblem(3, RowError.MISSING_NAME))), result)
        assertEquals(
            listOf(
                NutritionTableSummary(1, "first", 0, now.toEpochMilli(), 1),
                NutritionTableSummary(2, "second", 1, now.toEpochMilli(), 2),
            ),
            repository.tables.first(),
        )
        assertEquals(NutritionFoodEntity(1, "鸡蛋", "鸡子|蛋", 144.0), dao.foods.first())
    }

    @Test
    fun `a rejected file stores nothing`() = runTest {
        val repository = repository()
        assertEquals(TableImportResult.Failed(TableImportError.WRONG_HEADER), repository.import("bad") { "a,b\n1,2".byteInputStream() })
        assertEquals(TableImportResult.Failed(TableImportError.NO_VALID_ROWS, skipped = 1), repository.import("bad", csv("x,,y")))
        assertTrue(dao.tables.isEmpty())
    }

    @Test
    fun `a file that cannot be opened or read is unreadable`() = runTest {
        val repository = repository()
        assertEquals(TableImportResult.Failed(TableImportError.UNREADABLE), repository.import("x") { throw IOException("gone") })
        assertEquals(TableImportResult.Failed(TableImportError.UNREADABLE), repository.import("x") { throw SecurityException("revoked") })
    }

    @Test
    fun `a failed write reports save failed and leaves no half table`() = runTest {
        dao.failWrites = true
        assertEquals(TableImportResult.Failed(TableImportError.SAVE_FAILED, skipped = 1), repository().import("x", csv("a,,1", ",,1")))
        assertTrue(dao.tables.isEmpty() && dao.foods.isEmpty())
    }

    @Test
    fun `tables move up and down, and moves past either end do nothing`() = runTest {
        val repository = repository()
        listOf("a", "b", "c").forEach { repository.import(it, csv("x,,1")) }

        repository.move(3, -1)
        assertEquals(listOf("a", "c", "b"), repository.names())
        repository.move(1, +1)
        assertEquals(listOf("c", "a", "b"), repository.names())
        repository.move(3, -1)
        repository.move(2, +1)
        repository.move(99, -1)
        assertEquals(listOf("c", "a", "b"), repository.names())
        assertEquals(listOf(0, 1, 2), repository.tables.first().map { it.priority })
    }

    @Test
    fun `delete removes the table and its foods`() = runTest {
        val repository = repository()
        repository.import("a", csv("x,,1"))
        repository.import("b", csv("y,,2"))
        repository.delete(1)
        assertEquals(listOf("b"), repository.names())
        assertEquals(listOf("y"), dao.foods.map { it.name })

        // A new table still goes after the remaining ones.
        repository.import("c", csv("z,,3"))
        assertEquals(listOf("b", "c"), repository.names())
    }
}
