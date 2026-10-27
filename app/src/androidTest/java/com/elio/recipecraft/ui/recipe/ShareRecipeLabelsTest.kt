package com.elio.recipecraft.ui.recipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The shared text takes the recipe's language from the app's resources, whatever language the app shows. */
@RunWith(AndroidJUnit4::class)
class ShareRecipeLabelsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recipe(language: Language, name: String, ingredient: Ingredient) = Recipe(
        id = "a",
        name = name,
        mealType = MealType.DINNER,
        servings = 2,
        calories = Calories(300, 150, CalorieSource.DATABASE, 280),
        ingredients = listOf(ingredient),
        tools = emptyList(),
        steps = listOf(Step(1, "…", 5)),
        language = language,
        setId = null,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun chineseRecipeIsSharedInChinese() {
        val text = shareText(recipe(Language.ZH, "番茄炒蛋", Ingredient("鸡蛋", "egg", "2", "个", 100.0, 140)), shareLabels(context, Language.ZH))
        assertEquals(
            listOf("# 番茄炒蛋", "", "晚餐 · 2 人份", "数据库 · 共 300 千卡 · 每份 150 千卡", "_热量为估算值，不构成医疗建议。_", "", "## 食材", "- 鸡蛋 2个 (100 克)", "", "## 步骤", "1. … (5 分钟)"),
            text.lines(),
        )
    }

    @Test
    fun englishRecipeIsSharedInEnglish() {
        val text = shareText(recipe(Language.EN, "Omelette", Ingredient("Eggs", "egg", "3", "", 150.0, 210)), shareLabels(context, Language.EN))
        assertEquals(
            listOf(
                "# Omelette", "", "Dinner · 2 servings", "Database · 300 kcal total · 150 kcal per serving",
                "_Calories are approximate and not medical advice._", "", "## Ingredients", "- 3 Eggs (150 g)", "", "## Steps", "1. … (5 min)",
            ),
            text.lines(),
        )
    }
}
