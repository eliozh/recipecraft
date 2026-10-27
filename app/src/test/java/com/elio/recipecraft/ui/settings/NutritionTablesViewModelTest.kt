package com.elio.recipecraft.ui.settings

import com.elio.recipecraft.data.nutrition.CsvDocuments
import com.elio.recipecraft.data.nutrition.CsvTableImporter
import com.elio.recipecraft.data.nutrition.NutritionTableRepository
import com.elio.recipecraft.data.nutrition.RowError
import com.elio.recipecraft.data.nutrition.RowProblem
import com.elio.recipecraft.data.nutrition.TableImportError
import com.elio.recipecraft.data.nutrition.TableImportResult
import com.elio.recipecraft.testing.FakeNutritionTableDao
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class NutritionTablesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeNutritionTableDao()

    private class FakeDocuments : CsvDocuments {
        val files = mutableMapOf<String, Pair<String?, String>>()
        override suspend fun displayName(uri: String): String? = files[uri]?.first
        override fun open(uri: String): InputStream = files[uri]?.second?.byteInputStream() ?: throw FileNotFoundException()
    }

    private val documents = FakeDocuments()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = NutritionTablesViewModel(
        NutritionTableRepository(dao, CsvTableImporter(), Clock.systemUTC(), StandardTestDispatcher(testScheduler)),
        documents,
    )

    private fun TestScope.import(vm: NutritionTablesViewModel, uri: String, fileName: String?, csv: String) {
        documents.files[uri] = fileName to csv
        vm.onFilePicked(uri)
        advanceUntilIdle()
        vm.onConfirmImport()
        advanceUntilIdle()
    }

    @Test
    fun `a picked file is named after the file without its extension, and the name can be edited`() = runTest(dispatcher) {
        val vm = viewModel()
        documents.files["content://1"] = "中国食物成分表.CSV" to ""
        vm.onFilePicked("content://1")
        advanceUntilIdle()
        assertEquals(PendingImport("content://1", "中国食物成分表"), vm.uiState.value.pendingImport)

        vm.onPendingNameChanged("x".repeat(100))
        assertEquals(NutritionTablesViewModel.MAX_NAME_LENGTH, vm.uiState.value.pendingImport!!.name.length)
        vm.onDismissPendingImport()
        assertNull(vm.uiState.value.pendingImport)
    }

    @Test
    fun `backing out of the picker does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked(null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingImport)
    }

    @Test
    fun `an import shows its summary and the new table`() = runTest(dispatcher) {
        val vm = viewModel()
        import(vm, "content://1", "mine.csv", "name,aliases,kcal_per_100g\n鸡蛋,,144\n,,1\n番茄,,15")

        val state = vm.uiState.value
        assertFalse(state.importing)
        assertNull(state.pendingImport)
        assertEquals("mine" to TableImportResult.Imported(1, 2, 1, listOf(RowProblem(3, RowError.MISSING_NAME))), state.lastImport)
        assertEquals(listOf("mine" to 2), state.tables.map { it.name to it.foodCount })

        vm.onDismissImportSummary()
        assertNull(vm.uiState.value.lastImport)
    }

    @Test
    fun `a blank name cannot be imported`() = runTest(dispatcher) {
        val vm = viewModel()
        documents.files["content://1"] = null to "name,aliases,kcal_per_100g\n鸡蛋,,144"
        vm.onFilePicked("content://1")
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.pendingImport!!.name)
        vm.onConfirmImport()
        advanceUntilIdle()
        assertTrue(dao.tables.isEmpty())
        assertTrue(vm.uiState.value.pendingImport != null)
    }

    @Test
    fun `a failed import shows the error`() = runTest(dispatcher) {
        val vm = viewModel()
        import(vm, "content://1", "bad.csv", "名称,热量\n鸡蛋,144")
        assertEquals("bad" to TableImportResult.Failed(TableImportError.WRONG_HEADER), vm.uiState.value.lastImport)

        vm.onFilePicked("content://gone")
        advanceUntilIdle()
        vm.onPendingNameChanged("gone")
        vm.onConfirmImport()
        advanceUntilIdle()
        assertEquals("gone" to TableImportResult.Failed(TableImportError.UNREADABLE), vm.uiState.value.lastImport)
    }

    @Test
    fun `tables reorder and delete after confirmation`() = runTest(dispatcher) {
        val vm = viewModel()
        import(vm, "content://a", "a.csv", "name,aliases,kcal_per_100g\nx,,1")
        import(vm, "content://b", "b.csv", "name,aliases,kcal_per_100g\ny,,1")

        vm.onMoveUp(2)
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), vm.uiState.value.tables.map { it.name })
        vm.onMoveDown(2)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.uiState.value.tables.map { it.name })

        val a = vm.uiState.value.tables.first()
        vm.onDeleteRequested(a)
        assertEquals(a, vm.uiState.value.confirmDelete)
        vm.onDeleteDismissed()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.tables.size)

        vm.onDeleteRequested(a)
        vm.onDeleteConfirmed()
        advanceUntilIdle()
        assertNull(vm.uiState.value.confirmDelete)
        assertEquals(listOf("b"), vm.uiState.value.tables.map { it.name })
    }

    @Test
    fun `a failed write shows an error instead of crashing`() = runTest(dispatcher) {
        val vm = viewModel()
        import(vm, "content://a", "a.csv", "name,aliases,kcal_per_100g\nx,,1")
        dao.failWrites = true
        vm.onDeleteRequested(vm.uiState.value.tables.single())
        vm.onDeleteConfirmed()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionFailed)
        assertEquals(1, vm.uiState.value.tables.size)
    }

    @Test
    fun `an unreadable database shows a load error`() = runTest(dispatcher) {
        dao.failReads = true
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loadFailed)
    }
}
