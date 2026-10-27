package com.elio.recipecraft.data.nutrition

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class NutritionTableDao {

    /** The imported tables in priority order. Joins the foods, so Room re-emits when either table changes. */
    @Query(
        """
        SELECT t.id, t.name, t.priority, t.importedAt, COUNT(f.name) AS foodCount
        FROM nutrition_tables t LEFT JOIN nutrition_foods f ON f.tableId = t.id
        GROUP BY t.id
        ORDER BY t.priority, t.id
        """,
    )
    abstract fun observeTables(): Flow<List<NutritionTableSummary>>

    @Query("SELECT * FROM nutrition_foods WHERE tableId = :tableId")
    abstract suspend fun foods(tableId: Long): List<NutritionFoodEntity>

    @Query("SELECT id FROM nutrition_tables ORDER BY priority, id")
    abstract suspend fun tableIdsInOrder(): List<Long>

    @Query("SELECT MAX(priority) FROM nutrition_tables")
    abstract suspend fun maxPriority(): Int?

    @Insert
    abstract suspend fun insertTable(table: NutritionTableEntity): Long

    @Insert
    abstract suspend fun insertFoods(foods: List<NutritionFoodEntity>)

    @Query("UPDATE nutrition_tables SET priority = :priority WHERE id = :id")
    abstract suspend fun setPriority(id: Long, priority: Int)

    /** Deletes the table; its foods go with it through the cascading foreign key. */
    @Query("DELETE FROM nutrition_tables WHERE id = :id")
    abstract suspend fun deleteTable(id: Long)

    /** Adds a table after every existing one, with its foods, in one transaction. Returns its id. */
    @Transaction
    open suspend fun insertTableWithFoods(name: String, importedAt: Long, foods: List<NutritionFood>): Long {
        val id = insertTable(NutritionTableEntity(name = name, priority = (maxPriority() ?: -1) + 1, importedAt = importedAt))
        insertFoods(foods.map { it.toEntity(id) })
        return id
    }

    /** Gives the tables in [ids] the priorities 0, 1, 2, … in that order. */
    @Transaction
    open suspend fun setOrder(ids: List<Long>) {
        ids.forEachIndexed { priority, id -> setPriority(id, priority) }
    }
}
