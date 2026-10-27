package com.elio.recipecraft.ui.library

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.data.recipe.AndroidRecipeDocuments
import com.elio.recipecraft.data.recipe.ExportImportService
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.exportFileName
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.ui.recipe.UndoDelete
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.io.File
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the Library menu over a real Room database and real files: Export all, delete every
 * recipe, Import the same file. The system picker is replaced by a registry that answers with a
 * file in the app's cache, so the rest of the path, through the ContentResolver, is the app's own.
 */
@RunWith(AndroidJUnit4::class)
class ExportImportTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val file = File(context.cacheDir, "export-test.json").apply { delete() }

    @After
    fun close() {
        database.close()
        file.delete()
    }

    private val eggsOnly = object : NutritionLookups {
        private val lookups = listOf<NutritionLookup>(TableLookup(IngredientMatcher(listOf(NutritionFood("egg", emptyList(), 143.0)))))
        override suspend fun current() = lookups
        override fun observe() = flowOf(lookups)
    }
    private val repository by lazy { RecipeRepository(database.recipes(), eggsOnly, Dispatchers.IO) }

    private val omelette = Recipe(
        id = "omelette-1",
        name = "Cheese omelette",
        mealType = MealType.BREAKFAST,
        servings = 2,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 420),
        ingredients = listOf(Ingredient("Eggs", "egg", "3", "", 150.0, 210)),
        tools = listOf("Pan"),
        steps = listOf(Step(1, "Whisk the eggs.", 2), Step(2, "Cook until set.", null)),
        language = Language.EN,
        setId = "set-1",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
    )
    private val tomatoEggs = Recipe(
        id = "zh-1",
        name = "番茄炒蛋",
        mealType = null,
        servings = 1,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 300),
        ingredients = listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 140), Ingredient("番茄", "tomato", "1", "个", 150.5, 30)),
        tools = emptyList(),
        steps = listOf(Step(1, "炒熟。", 5)),
        language = Language.ZH,
        setId = null,
        createdAt = 1_600_000_000_000,
        updatedAt = 1_600_000_000_000,
    )

    /** Answers every launch with [file], as if the user picked it, and keeps what was asked for. */
    private val registry = object : ActivityResultRegistry() {
        val inputs = mutableListOf<Any?>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            inputs += input
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, Uri.fromFile(file) as O)
        }
    }

    private fun setContent() {
        val library = LibraryViewModel(
            repository,
            UndoDelete(),
            ExportImportService(repository, Clock.systemUTC(), Dispatchers.IO),
            AndroidRecipeDocuments(context),
        )
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RecipeCraftTheme { LibraryScreen(onOpenChat = {}, onOpenRecipe = {}, viewModel = library) }
            }
        }
    }

    private fun waitForTag(tag: String, count: Int = 1) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == count }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun exportDeleteEverythingAndImportRestoresIdenticalRecipes() {
        runBlocking { repository.save(listOf(omelette, tomatoEggs)) }
        val before = runBlocking { database.recipes().all().sortedBy { it.id } }
        setContent()
        waitForTag(LibraryTestTags.ITEM, 2)

        compose.onNodeWithTag(LibraryTestTags.MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.EXPORT_ALL).assertIsEnabled().performClick()
        waitForText(context.resources.getQuantityString(R.plurals.library_exported, 2, 2))
        assertEquals(exportFileName(LocalDate.now()), registry.inputs.single())

        val json = file.readText()
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("番茄炒蛋"))
        assertFalse(json.contains("PLACEHOLDER"))

        runBlocking { before.forEach { repository.delete(it.id) } }
        waitForTag(LibraryTestTags.EMPTY)

        compose.onNodeWithTag(LibraryTestTags.MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.EXPORT_ALL).assertIsNotEnabled()
        compose.onNodeWithTag(LibraryTestTags.IMPORT).performClick()
        waitForTag(LibraryTestTags.IMPORT_SUMMARY)
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.library_import_imported, 2, 2)).assertExists()
        assertEquals(listOf(IMPORT_MIME_TYPES.toList()), registry.inputs.drop(1).map { (it as Array<*>).toList() })
        assertEquals(before, runBlocking { database.recipes().all().sortedBy { it.id } })

        // Importing again adds nothing and says the recipes are already there.
        compose.onNodeWithText(context.getString(R.string.action_ok)).performClick()
        compose.onNodeWithTag(LibraryTestTags.MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.IMPORT).performClick()
        waitForText(context.resources.getQuantityString(R.plurals.library_import_duplicates, 2, 2))
        assertEquals(before, runBlocking { database.recipes().all().sortedBy { it.id } })
    }

    @Test
    fun aFileFromANewerVersionIsRejectedWithAClearMessage() {
        file.writeText("""{"version": 2, "exportedAt": 0, "recipes": []}""")
        setContent()
        waitForTag(LibraryTestTags.EMPTY)

        compose.onNodeWithTag(LibraryTestTags.MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.IMPORT).performClick()
        waitForTag(LibraryTestTags.IMPORT_SUMMARY)
        compose.onNodeWithText(context.getString(R.string.recipe_import_error_newer_version)).assertExists()
    }
}
