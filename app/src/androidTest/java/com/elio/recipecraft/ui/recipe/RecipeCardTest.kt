package com.elio.recipecraft.ui.recipe

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.data.llm.RecipeAssembler
import com.elio.recipecraft.data.llm.RecipeParseResult
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.nutrition.CalorieCalculator
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.UsdaDatabase
import com.elio.recipecraft.data.nutrition.UsdaNutritionLookups
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.io.File
import java.time.Clock
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders cards built from the synthetic reply fixtures shared with the JVM tests. */
@RunWith(AndroidJUnit4::class)
class RecipeCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recipeFrom(fixture: String, language: Language, lookups: List<NutritionLookup> = emptyList()): Recipe {
        val reply = javaClass.getResourceAsStream("/fixtures/replies/$fixture")!!.bufferedReader().readText()
        val batch = (RecipeParser().parse(reply) as RecipeParseResult.Success).batch
        return RecipeAssembler(Clock.systemUTC()).assemble(batch, language, lookups).recipes.single()
    }

    /** The real bundled USDA table, opened under a throwaway name. */
    private fun withUsda(block: (List<NutritionLookup>) -> Unit) {
        val name = "usda-card-test.db"
        context.deleteDatabase(name)
        val database = UsdaDatabase.open(context, name)
        try {
            block(runBlocking { UsdaNutritionLookups(database.foods(), Dispatchers.IO).current() })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun setCard(recipe: Recipe, locale: Locale) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                RecipeCraftTheme {
                    Surface {
                        RecipeCard(recipe, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
                    }
                }
            }
        }
    }

    @Test
    fun englishRecipeShowsEverySection() {
        setCard(recipeFrom("en_breakfast.txt", Language.EN), Locale.ENGLISH)

        compose.onNodeWithText("Banana oatmeal with milk").assertExists()
        compose.onNodeWithText("Breakfast · 2 servings").assertExists()
        compose.onNodeWithText("≈ AI estimate").assertExists()
        compose.onNodeWithText("560 kcal total · 280 kcal per serving").assertExists()
        compose.onNodeWithText("1 cup Rolled oats").assertExists()
        compose.onNodeWithText("1 Banana").assertExists()
        compose.onNodeWithText("Ground cinnamon, a pinch").assertExists()
        compose.onNodeWithText("Small saucepan").assertExists()
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("Bring the milk to a gentle simmer in the saucepan.").assertExists()
        compose.onNodeWithText("3 min").assertExists()
        saveScreenshot("recipe_card_en")

        compose.onNodeWithContentDescription("Hide Ingredients").performClick()
        compose.onNodeWithText("1 cup Rolled oats").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show Ingredients").performClick()
        compose.onNodeWithText("1 cup Rolled oats").assertExists()
    }

    @Test
    fun chineseRecipeShowsEverySectionInChinese() {
        setCard(recipeFrom("zh_breakfast.txt", Language.ZH), Locale.SIMPLIFIED_CHINESE)

        compose.onNodeWithText("葱花鸡蛋饼").assertExists()
        compose.onNodeWithText("早餐 · 2 人份").assertExists()
        compose.onNodeWithText("≈ AI 估算").assertExists()
        compose.onNodeWithText("共 640 千卡 · 每份 320 千卡").assertExists()
        compose.onNodeWithText("鸡蛋 2个").assertExists()
        compose.onNodeWithText("盐 适量").assertExists()
        compose.onNodeWithText("平底锅").assertExists()
        compose.onNodeWithText("小葱切成葱花。").assertExists()
        compose.onNodeWithText("6 分钟").assertExists()
        compose.onNodeWithTag(RecipeCardTestTags.STEPS).assertExists()
        saveScreenshot("recipe_card_zh")

        compose.onNodeWithContentDescription("收起步骤").performClick()
        compose.onNodeWithText("小葱切成葱花。").assertDoesNotExist()
    }

    @Test
    fun matchedRecipeShowsTheDatabaseTotalAndDisclaimer() = withUsda { usda ->
        setCard(recipeFrom("en_breakfast.txt", Language.EN, usda), Locale.ENGLISH)

        compose.onNodeWithText("Database").assertExists()
        compose.onNodeWithText("≈ AI estimate").assertDoesNotExist()
        compose.onNodeWithText("653 kcal total · 327 kcal per serving").assertExists()
        compose.onNodeWithText("Calories are approximate and not medical advice.").assertExists()
        saveScreenshot("recipe_card_database")
    }

    @Test
    fun oneUnmatchedIngredientShowsTheAiEstimate() = withUsda { usda ->
        val matched = recipeFrom("en_breakfast.txt", Language.EN, usda)
        val ingredients = matched.ingredients.map { if (it.nameEn == "honey") it.copy(nameEn = "yuzu marmalade") else it }
        val recipe = matched.copy(ingredients = ingredients).let { it.copy(calories = CalorieCalculator.calculate(it, usda)) }
        setCard(recipe, Locale.ENGLISH)

        compose.onNodeWithText("≈ AI estimate").assertExists()
        compose.onNodeWithText("Database").assertDoesNotExist()
        compose.onNodeWithText("560 kcal total · 280 kcal per serving").assertExists()
        saveScreenshot("recipe_card_ai_estimate")
    }

    @Test
    fun chineseCardShowsTheDisclaimerInChinese() {
        setCard(recipeFrom("zh_breakfast.txt", Language.ZH), Locale.SIMPLIFIED_CHINESE)

        compose.onNodeWithText("热量为估算值，不构成医疗建议。").assertExists()
    }

    /** Kept in the app's external files dir so a manual run can `adb pull` it. */
    private fun saveScreenshot(name: String) {
        val dir = context.getExternalFilesDir("screenshots") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
