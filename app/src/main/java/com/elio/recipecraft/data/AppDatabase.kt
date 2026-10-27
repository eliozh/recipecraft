package com.elio.recipecraft.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.elio.recipecraft.data.nutrition.NutritionFoodEntity
import com.elio.recipecraft.data.nutrition.NutritionTableDao
import com.elio.recipecraft.data.nutrition.NutritionTableEntity

/** The user's data. The bundled USDA table lives apart, in [com.elio.recipecraft.data.nutrition.UsdaDatabase]. */
@Database(
    entities = [NutritionTableEntity::class, NutritionFoodEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nutritionTables(): NutritionTableDao

    companion object {
        const val FILE_NAME = "recipecraft.db"
    }
}
