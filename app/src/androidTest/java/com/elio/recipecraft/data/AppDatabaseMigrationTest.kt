package com.elio.recipecraft.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks each migration against the schemas exported to app/schemas. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java)

    @Before
    fun clean() {
        instrumentation.targetContext.deleteDatabase(DB)
    }

    @Test
    fun migrate1To2AddsRecipesAndKeepsImportedTables() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO nutrition_tables (id, name, priority, importedAt) VALUES (1, 'Mine', 0, 5)")
            db.execSQL("INSERT INTO nutrition_foods (tableId, name, aliases, kcalPer100g) VALUES (1, '鸡蛋', 'egg', 143.0)")
        }

        helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT COUNT(*) FROM nutrition_foods").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM recipes").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }

        // The app's own builder opens the migrated file and reads the kept table.
        val database = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, DB)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val tables = runBlocking { database.nutritionTables().observeTables().first() }
            assertEquals(listOf("Mine" to 1), tables.map { it.name to it.foodCount })
            assertEquals(0, runBlocking { database.recipes().observeCount().first() })
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
