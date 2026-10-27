package com.elio.recipecraft.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elio.recipecraft.data.nutrition.NutritionFoodEntity
import com.elio.recipecraft.data.nutrition.NutritionTableDao
import com.elio.recipecraft.data.nutrition.NutritionTableEntity
import com.elio.recipecraft.data.recipe.Converters
import com.elio.recipecraft.data.recipe.RecipeDao
import com.elio.recipecraft.data.recipe.RecipeEntity

/** The user's data. The bundled USDA table lives apart, in [com.elio.recipecraft.data.nutrition.UsdaDatabase]. */
@Database(
    entities = [NutritionTableEntity::class, NutritionFoodEntity::class, RecipeEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nutritionTables(): NutritionTableDao

    abstract fun recipes(): RecipeDao

    companion object {
        const val FILE_NAME = "recipecraft.db"

        /** Adds the saved recipes; imported nutrition tables are kept. AppDatabaseMigrationTest checks it against the exported schemas. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recipes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mealType` TEXT, " +
                        "`servings` INTEGER NOT NULL, `calories` TEXT NOT NULL, `ingredients` TEXT NOT NULL, " +
                        "`tools` TEXT NOT NULL, `steps` TEXT NOT NULL, `language` TEXT NOT NULL, `setId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `searchText` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recipes_createdAt` ON `recipes` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recipes_mealType` ON `recipes` (`mealType`)")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
