package com.elio.recipecraft.data.nutrition

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The bundled USDA FoodData Central table, kept apart from the user's data so its migrations never
 * touch recipes. The app only reads it.
 */
@Database(entities = [UsdaFoodEntity::class], version = 1, exportSchema = true)
abstract class UsdaDatabase : RoomDatabase() {
    abstract fun foods(): UsdaFoodDao

    companion object {
        const val ASSET_PATH = "nutrition/usda.db"
        const val FILE_NAME = "usda.db"

        /**
         * Destructive migration is safe here, and only here: the file holds nothing but the asset,
         * so bumping [UsdaDatabase]'s version simply copies the new asset over the old copy.
         */
        fun open(context: Context, name: String = FILE_NAME): UsdaDatabase =
            Room.databaseBuilder(context, UsdaDatabase::class.java, name)
                .createFromAsset(ASSET_PATH)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
