package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeRepositoryTest {

    private val dao = FakeRecipeDao()
    private val nutrition = FakeNutritionLookups()
    private val repository = RecipeRepository(dao, nutrition, UnconfinedTestDispatcher())

    @Test
    fun `a recipe survives the trip to an entity and back`() {
        val recipe = testRecipe(
            "a",
            name = "番茄炒蛋",
            mealType = null,
            ingredients = listOf(ingredient("鸡蛋", "egg"), ingredient("番茄", "tomato", grams = null)),
            language = Language.ZH,
            setId = "set-1",
        )
        assertEquals(recipe, recipe.toEntity().toDomain())
    }

    @Test
    fun `the search text holds the name and both names of every ingredient, lowercased`() {
        val recipe = testRecipe("a", name = "Tomato EGGS", ingredients = listOf(ingredient("鸡蛋", "Egg"), ingredient("Whole Milk")))
        assertEquals("tomato eggs\n鸡蛋\negg\nwhole milk\nwhole milk", recipe.toEntity().searchText)
    }

    @Test
    fun `saving the same recipe twice keeps one copy`() = runTest {
        val recipe = testRecipe("a")
        repository.save(listOf(recipe))
        repository.save(listOf(recipe.copy(name = "Renamed")))

        assertEquals(listOf("a" to "Renamed"), dao.recipes.map { it.id to it.name })
        assertEquals(setOf("a"), repository.savedIds.first())
        assertEquals(1, repository.count.first())
    }

    @Test
    fun `saving works calories out against the current tables`() = runTest {
        nutrition.lookups = listOf(lookupOf("egg" to 143.0))
        repository.save(listOf(testRecipe("a", aiTotal = 300)))

        assertEquals(Calories(143, 72, CalorieSource.DATABASE, 300), dao.recipes.single().calories)
    }

    @Test
    fun `saving nothing writes nothing`() = runTest {
        dao.failWrites = true
        repository.save(emptyList())
        assertTrue(dao.recipes.isEmpty())
    }

    @Test
    fun `observe filters by meal type and searches names and ingredients ignoring case`() = runTest {
        repository.save(
            listOf(
                testRecipe("old", name = "Oatmeal", mealType = MealType.BREAKFAST, createdAt = 1),
                testRecipe("new", name = "番茄炒蛋", mealType = MealType.DINNER, ingredients = listOf(ingredient("番茄", "tomato")), createdAt = 2),
            ),
        )

        assertEquals(listOf("new", "old"), repository.observe(null, "").first().map { it.id })
        assertEquals(listOf("old"), repository.observe(MealType.BREAKFAST, "").first().map { it.id })
        assertEquals(listOf("old"), repository.observe(null, "  OATMEAL ").first().map { it.id })
        assertEquals(listOf("new"), repository.observe(null, "番茄").first().map { it.id })
        assertEquals(listOf("new"), repository.observe(null, "Tomato").first().map { it.id })
        assertEquals(emptyList<String>(), repository.observe(MealType.BREAKFAST, "tomato").first().map { it.id })
    }

    @Test
    fun `LIKE wildcards in a search are matched literally`() = runTest {
        repository.save(listOf(testRecipe("a", name = "100% oats"), testRecipe("b", name = "1000 oats")))
        assertEquals(listOf("a"), repository.observe(null, "100%").first().map { it.id })
        assertEquals("a\\%b\\_c\\\\d", RecipeDao.escapeLike("a%b_c\\d"))
    }

    @Test
    fun `recalculating writes only the recipes whose calories changed and keeps the AI total`() = runTest {
        repository.save(
            listOf(
                testRecipe("eggs", ingredients = listOf(ingredient("egg")), aiTotal = 300),
                testRecipe("rice", ingredients = listOf(ingredient("rice")), aiTotal = 200),
            ),
        )

        assertEquals(1, repository.recalculate(listOf(lookupOf("egg" to 143.0))))
        assertEquals(listOf("eggs"), dao.calorieWrites)
        assertEquals(Calories(143, 72, CalorieSource.DATABASE, 300), dao.recipes.first { it.id == "eggs" }.calories)
        assertEquals(Calories(200, 100, CalorieSource.AI_ESTIMATE, 200), dao.recipes.first { it.id == "rice" }.calories)

        // Nothing changed, so nothing is written.
        assertEquals(0, repository.recalculate(listOf(lookupOf("egg" to 143.0))))
        assertEquals(listOf("eggs"), dao.calorieWrites)

        // The table goes away: back to the model's own estimate.
        assertEquals(1, repository.recalculate(emptyList()))
        assertEquals(Calories(300, 150, CalorieSource.AI_ESTIMATE, 300), dao.recipes.first { it.id == "eggs" }.calories)
    }

    @Test
    fun `the detail of a saved recipe carries each ingredient's database kcal`() = runTest {
        nutrition.lookups = listOf(lookupOf("egg" to 155.0))
        repository.save(listOf(testRecipe("a", ingredients = listOf(ingredient("egg", grams = 50.0), ingredient("caviar")))))

        val detail = repository.observeDetail("a").first()!!
        assertEquals("a", detail.recipe.id)
        assertEquals(listOf(78, null), detail.ingredientKcal)
        assertNull(repository.observeDetail("missing").first())
    }

    @Test
    fun `delete removes the recipe and restore puts back the identical record`() = runTest {
        nutrition.lookups = listOf(lookupOf("egg" to 155.0))
        repository.save(listOf(testRecipe("a", createdAt = 42), testRecipe("b")))
        val saved = dao.recipes.first { it.id == "a" }.toDomain()

        repository.delete("a")
        assertEquals(listOf("b"), dao.recipes.map { it.id })
        assertNull(repository.observeDetail("a").first())

        // A table change meanwhile must not alter the restored record's calories.
        nutrition.lookups = emptyList()
        repository.restore(saved)
        assertEquals(saved, repository.observeDetail("a").first()!!.recipe)
        assertEquals(saved.toEntity(), dao.recipes.first { it.id == "a" })
    }

    @Test
    fun `recipes in another language are listed newest first`() = runTest {
        repository.save(
            listOf(
                testRecipe("old", createdAt = 1),
                testRecipe("zh", language = Language.ZH, createdAt = 2),
                testRecipe("new", createdAt = 3),
            ),
        )
        assertEquals(listOf("new", "old"), repository.notIn(Language.ZH).map { it.id })
        assertEquals(listOf("zh"), repository.notIn(Language.EN).map { it.id })
    }

    @Test
    fun `update rewrites a saved recipe with fresh calories and never adds one`() = runTest {
        repository.save(listOf(testRecipe("a")))
        nutrition.lookups = listOf(lookupOf("egg" to 143.0))

        assertTrue(repository.update(testRecipe("a", name = "Renamed")))
        val saved = dao.recipes.single().toDomain()
        assertEquals("Renamed", saved.name)
        assertEquals(CalorieSource.DATABASE, saved.calories.source)

        assertEquals(false, repository.update(testRecipe("gone")))
        assertEquals(listOf("a"), dao.recipes.map { it.id })
    }

    @Test
    fun `replace rewrites the saved recipe in place, keeping its id and creation time`() = runTest {
        nutrition.lookups = listOf(lookupOf("egg" to 143.0))
        repository.save(listOf(testRecipe("a", setId = "set-1", createdAt = 1_000), testRecipe("b", createdAt = 2_000)))
        val changed = testRecipe("a", name = "Lighter", setId = "set-1", createdAt = 1_000, aiTotal = 200).copy(updatedAt = 5_000)

        assertTrue(repository.replace(changed))

        assertEquals(2, dao.recipes.size)
        val replaced = dao.recipes.first { it.id == "a" }.toDomain()
        assertEquals("Lighter", replaced.name)
        assertEquals("set-1", replaced.setId)
        assertEquals(1_000L, replaced.createdAt)
        assertEquals(5_000L, replaced.updatedAt)
        assertEquals(Calories(143, 72, CalorieSource.DATABASE, 200), replaced.calories)
        assertEquals("Recipe b", dao.recipes.first { it.id == "b" }.name)
    }

    @Test
    fun `replace never brings back a recipe deleted meanwhile`() = runTest {
        assertEquals(false, repository.replace(testRecipe("gone")))
        assertTrue(dao.recipes.isEmpty())
    }

    @Test
    fun `save as copy adds a new recipe outside the set and leaves the original as it was`() = runTest {
        nutrition.lookups = listOf(lookupOf("egg" to 143.0))
        repository.save(listOf(testRecipe("a", setId = "set-1", createdAt = 1_000)))
        val original = dao.recipes.single()
        val changed = testRecipe("a", name = "Lighter", setId = "set-1", createdAt = 1_000, aiTotal = 200).copy(updatedAt = 5_000)

        repository.saveAsCopy(changed, id = "copy-1", now = 9_000)

        assertEquals(2, dao.recipes.size)
        assertEquals(original, dao.recipes.first { it.id == "a" })
        val copy = dao.recipes.first { it.id == "copy-1" }.toDomain()
        assertEquals("Lighter", copy.name)
        assertNull(copy.setId)
        assertEquals(9_000L, copy.createdAt)
        assertEquals(9_000L, copy.updatedAt)
        assertEquals(Calories(143, 72, CalorieSource.DATABASE, 200), copy.calories)
        assertEquals(2, repository.count.first())
    }
}
