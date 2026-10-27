package com.elio.recipecraft.data.nutrition

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A nutrition table the user imported. Lower [priority] is tried first, and every imported table goes before USDA. */
@Entity(tableName = "nutrition_tables")
data class NutritionTableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priority: Int,
    /** Epoch milliseconds. */
    val importedAt: Long,
)

/** One row of an imported table. Names are unique within a table after [IngredientMatcher.normalize]. */
@Entity(
    tableName = "nutrition_foods",
    primaryKeys = ["tableId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = NutritionTableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tableId")],
)
data class NutritionFoodEntity(
    val tableId: Long,
    val name: String,
    /** Alternative keys separated by `|`; empty when there are none. */
    val aliases: String,
    val kcalPer100g: Double,
)

/** A table as the settings list shows it. */
data class NutritionTableSummary(
    val id: Long,
    val name: String,
    val priority: Int,
    val importedAt: Long,
    val foodCount: Int,
)

/** Splits a stored `|`-separated alias list, dropping blanks. */
internal fun splitAliases(aliases: String): List<String> = aliases.split('|').map(String::trim).filter(String::isNotEmpty)

internal fun NutritionFoodEntity.toFood() = NutritionFood(name, splitAliases(aliases), kcalPer100g)

internal fun NutritionFood.toEntity(tableId: Long) = NutritionFoodEntity(
    tableId = tableId,
    name = name,
    aliases = aliases.joinToString("|"),
    kcalPer100g = kcalPer100g,
)
