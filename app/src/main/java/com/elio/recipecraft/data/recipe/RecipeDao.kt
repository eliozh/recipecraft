package com.elio.recipecraft.data.recipe

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.MealType
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {

    /**
     * Saved recipes, newest first; recipes created together keep the order they were saved in.
     * [mealType] null means every meal type. [query] must already be lowercased and escaped with
     * [escapeLike]; empty means no search.
     */
    @Query(
        "SELECT * FROM recipes " +
            "WHERE (:mealType IS NULL OR mealType = :mealType) " +
            "AND (:query = '' OR searchText LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY createdAt DESC, rowid",
    )
    abstract fun observe(mealType: MealType?, query: String): Flow<List<RecipeEntity>>

    @Query("SELECT id FROM recipes")
    abstract fun observeIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM recipes")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT * FROM recipes")
    abstract suspend fun all(): List<RecipeEntity>

    /** Inserts new recipes and replaces saved ones with the same id, so saving twice never duplicates. */
    @Upsert
    abstract suspend fun upsert(recipes: List<RecipeEntity>)

    @Query("UPDATE recipes SET calories = :calories WHERE id = :id")
    abstract suspend fun setCalories(id: String, calories: Calories)

    /** Writes only the calories, so a recipe saved or edited meanwhile keeps its other fields. */
    @Transaction
    open suspend fun setCalories(changes: Map<String, Calories>) {
        changes.forEach { (id, calories) -> setCalories(id, calories) }
    }

    companion object {
        /** Escapes LIKE's wildcards in [text], to match the `ESCAPE '\'` of [observe]. */
        fun escapeLike(text: String): String =
            text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
