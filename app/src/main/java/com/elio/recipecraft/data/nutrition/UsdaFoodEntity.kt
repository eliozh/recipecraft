package com.elio.recipecraft.data.nutrition

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One curated row of the bundled USDA table. `tools/nutrition/build_usda.py` writes these rows,
 * so a change here needs a new schema export and a rebuilt `usda.db`.
 */
@Entity(tableName = "usda_foods")
data class UsdaFoodEntity(
    /** The simple, singular, lowercase English match key, such as `egg`. */
    @PrimaryKey val name: String,
    /** Alternative keys separated by `|`; empty when there are none. */
    val aliases: String,
    val kcalPer100g: Double,
    /** The FoodData Central food the energy value comes from. */
    val fdcId: Long,
)

@Dao
interface UsdaFoodDao {
    @Query("SELECT * FROM usda_foods ORDER BY name")
    suspend fun all(): List<UsdaFoodEntity>
}
