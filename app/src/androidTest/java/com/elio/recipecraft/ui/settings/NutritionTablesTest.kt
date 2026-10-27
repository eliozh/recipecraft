package com.elio.recipecraft.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elio.recipecraft.data.nutrition.NutritionTableSummary
import com.elio.recipecraft.data.nutrition.RowError
import com.elio.recipecraft.data.nutrition.RowProblem
import com.elio.recipecraft.data.nutrition.TableImportResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionTablesTest {

    @get:Rule
    val compose = createComposeRule()

    private val tables = listOf(
        NutritionTableSummary(1, "中国食物成分表", 0, 0, 4),
        NutritionTableSummary(2, "My table", 1, 0, 1),
    )

    @Test
    fun showsTheSummaryAndTheTablesWithTheirFoodCounts() {
        val moved = mutableListOf<Long>()
        var deleteRequested: NutritionTableSummary? = null
        compose.setContent {
            NutritionTables(
                state = NutritionTablesUiState(
                    tables = tables,
                    lastImport = "中国食物成分表" to TableImportResult.Imported(1, 4, 2, listOf(RowProblem(3, RowError.INVALID_KCAL))),
                ),
                onImport = {},
                onPendingNameChanged = {},
                onConfirmImport = {},
                onDismissPendingImport = {},
                onDismissImportSummary = {},
                onMoveUp = { moved += -it },
                onMoveDown = { moved += it },
                onDeleteRequested = { deleteRequested = it },
                onDeleteConfirmed = {},
                onDeleteDismissed = {},
            )
        }

        compose.onNodeWithText("Imported 4 foods into “中国食物成分表”.").assertExists()
        compose.onNodeWithText("Skipped 2 rows.").assertExists()
        compose.onNodeWithText("Row 3: kcal_per_100g must be a number, 0 or more").assertExists()
        compose.onNodeWithText("4 foods").assertExists()
        compose.onNodeWithText("1 food").assertExists()

        compose.onNodeWithContentDescription("Move 中国食物成分表 up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move My table down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move My table up").performClick()
        compose.onNodeWithContentDescription("Move 中国食物成分表 down").performClick()
        compose.onNodeWithContentDescription("Delete My table").performClick()
        assertEquals(listOf(-2L, 1L), moved)
        assertEquals(tables[1], deleteRequested)
    }

    @Test
    fun deleteAsksForConfirmation() {
        var confirmed = false
        compose.setContent {
            NutritionTables(
                state = NutritionTablesUiState(tables = tables, confirmDelete = tables[1]),
                onImport = {},
                onPendingNameChanged = {},
                onConfirmImport = {},
                onDismissPendingImport = {},
                onDismissImportSummary = {},
                onMoveUp = {},
                onMoveDown = {},
                onDeleteRequested = {},
                onDeleteConfirmed = { confirmed = true },
                onDeleteDismissed = {},
            )
        }
        compose.onNodeWithText("Delete this table?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, confirmed)
    }
}
