package com.elio.recipecraft.data.recipe

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real Room DAO, converters and queries on an in-memory [AppDatabase]. */
@RunWith(AndroidJUnit4::class)
class RecipeDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecipeDao
    private lateinit var repository: RecipeRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.recipes()
        val noTables = object : NutritionLookups {
            override suspend fun current() = emptyList<NutritionLookup>()
            override fun observe() = flowOf(emptyList<NutritionLookup>())
        }
        repository = RecipeRepository(dao, noTables, Dispatchers.IO)
    }

    @After
    fun close() = database.close()

    private fun recipe(
        id: String,
        name: String,
        mealType: MealType? = MealType.BREAKFAST,
        ingredients: List<Ingredient> = listOf(Ingredient("egg", "egg", "2", "", 100.0, 143)),
        language: Language = Language.EN,
        createdAt: Long = 1,
    ) = Recipe(
        id = id,
        name = name,
        mealType = mealType,
        servings = 2,
        calories = Calories(300, 150, CalorieSource.AI_ESTIMATE, 300),
        ingredients = ingredients,
        tools = listOf("pan"),
        steps = listOf(Step(1, "Cook", 5), Step(2, "Serve", null)),
        language = language,
        setId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun ids(mealType: MealType? = null, query: String = "") = runBlocking {
        repository.observe(mealType, query).first().map { it.id }
    }

    @Test
    fun aSavedRecipeReadsBackWithEveryField() = runBlocking {
        val saved = recipe("a", "番茄炒蛋", mealType = null, language = Language.ZH).copy(
            setId = "set-1",
            ingredients = listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 143), Ingredient("盐", "salt", "适量", "", null, null)),
        )
        repository.save(listOf(saved))
        assertEquals(listOf(saved), repository.observe(null, "").first())
    }

    @Test
    fun upsertingTheSameIdKeepsOneRowInItsPlace() = runBlocking {
        dao.upsert(listOf(recipe("a", "First").toEntity(), recipe("b", "Second").toEntity()))
        dao.upsert(listOf(recipe("a", "First, renamed").toEntity()))

        assertEquals(2, dao.observeCount().first())
        assertEquals(listOf("a" to "First, renamed", "b" to "Second"), dao.all().map { it.id to it.name })
        assertEquals(listOf("a", "b"), ids())
    }

    @Test
    fun newestFirstAndRecipesSavedTogetherKeepTheirOrder() = runBlocking {
        repository.save(listOf(recipe("old", "Old", createdAt = 1)))
        repository.save(listOf(recipe("lunch", "Lunch", createdAt = 2), recipe("dinner", "Dinner", createdAt = 2)))
        assertEquals(listOf("lunch", "dinner", "old"), ids())
    }

    @Test
    fun filterByMealType() = runBlocking {
        repository.save(
            listOf(
                recipe("b", "Oatmeal", MealType.BREAKFAST),
                recipe("d", "Stew", MealType.DINNER),
                recipe("n", "Mystery", mealType = null),
            ),
        )
        assertEquals(listOf("b"), ids(MealType.BREAKFAST))
        assertEquals(listOf("d"), ids(MealType.DINNER))
        assertEquals(emptyList<String>(), ids(MealType.SNACK))
        assertEquals(setOf("b", "d", "n"), ids().toSet())
    }

    @Test
    fun searchByNameAndIngredientInBothLanguagesIgnoringCase() = runBlocking {
        repository.save(
            listOf(
                recipe("oats", "Banana Oatmeal", ingredients = listOf(Ingredient("Rolled oats", "rolled oats", "1", "cup", 80.0, 300))),
                recipe(
                    "eggs",
                    "番茄炒蛋",
                    MealType.DINNER,
                    ingredients = listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 143), Ingredient("番茄", "tomato", "2", "个", 200.0, 36)),
                    language = Language.ZH,
                ),
            ),
        )
        assertEquals(listOf("oats"), ids(query = "OATMEAL"))
        assertEquals(listOf("oats"), ids(query = "rolled OATS"))
        assertEquals(listOf("eggs"), ids(query = "番茄"))
        assertEquals(listOf("eggs"), ids(query = "鸡蛋"))
        assertEquals(listOf("eggs"), ids(query = "Tomato"))
        assertEquals(listOf("eggs"), ids(MealType.DINNER, "炒"))
        assertEquals(emptyList<String>(), ids(MealType.BREAKFAST, "番茄"))
        assertEquals(emptyList<String>(), ids(query = "%"))
        assertEquals(emptyList<String>(), ids(query = "_"))
    }

    @Test
    fun setCaloriesChangesOnlyTheCalories() = runBlocking {
        repository.save(listOf(recipe("a", "Eggs"), recipe("b", "More eggs")))
        val calories = Calories(143, 72, CalorieSource.DATABASE, 300)
        val seen = recipe("a", "Eggs").updatedAt
        assertEquals(1, dao.setCalories(listOf(RecipeDao.CalorieChange("a", calories, seen))))

        val (a, b) = dao.all()
        assertEquals(recipe("a", "Eggs").copy(calories = calories), a.toDomain())
        assertEquals(recipe("b", "More eggs"), b.toDomain())
    }

    @Test
    fun setCaloriesSkipsARecipeChangedSinceItWasRead() = runBlocking {
        repository.save(listOf(recipe("a", "Eggs")))
        val seen = dao.all().single().updatedAt
        dao.update(dao.all().single().copy(name = "Oeufs", updatedAt = seen + 1))

        val calories = Calories(143, 72, CalorieSource.DATABASE, 300)
        assertEquals(0, dao.setCalories(listOf(RecipeDao.CalorieChange("a", calories, seen))))
        assertNotEquals(calories, dao.all().single().calories)
    }
}
