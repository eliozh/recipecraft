package com.elio.recipecraft.ui.recipe

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The labels follow `values/strings.xml` and `values-zh/strings.xml`. */
class ShareRecipeTest {

    private object English : ShareLabels {
        override val locale: Locale = Locale.ENGLISH
        override val ingredients = "Ingredients"
        override val tools = "Tools"
        override val steps = "Steps"
        override val disclaimer = "Calories are approximate and not medical advice."
        override val caloriesUnknown = "Not estimated"
        override fun mealType(type: MealType) = type.name.lowercase().replaceFirstChar { it.uppercase() }
        override fun servings(count: Int) = if (count == 1) "1 serving" else "$count servings"
        override fun source(source: CalorieSource) = if (source == CalorieSource.DATABASE) "Database" else "≈ AI estimate"
        override fun caloriesTotal(kcal: Int) = "$kcal kcal total"
        override fun caloriesPerServing(kcal: Int) = "$kcal kcal per serving"
        override fun ingredientLine(quantity: String, unit: String, name: String) = "$quantity $unit $name"
        override fun ingredientUnquantified(name: String, unit: String) = "$name, $unit"
        override fun grams(formatted: String) = "$formatted g"
        override fun minutes(minutes: Int) = "$minutes min"
    }

    private object Chinese : ShareLabels {
        override val locale: Locale = Locale.SIMPLIFIED_CHINESE
        override val ingredients = "食材"
        override val tools = "厨具"
        override val steps = "步骤"
        override val disclaimer = "热量为估算值，不构成医疗建议。"
        override val caloriesUnknown = "未估算"
        override fun mealType(type: MealType) = if (type == MealType.DINNER) "晚餐" else "早餐"
        override fun servings(count: Int) = "$count 人份"
        override fun source(source: CalorieSource) = if (source == CalorieSource.DATABASE) "数据库" else "≈ AI 估算"
        override fun caloriesTotal(kcal: Int) = "共 $kcal 千卡"
        override fun caloriesPerServing(kcal: Int) = "每份 $kcal 千卡"
        override fun ingredientLine(quantity: String, unit: String, name: String) = "$name $quantity$unit"
        override fun ingredientUnquantified(name: String, unit: String) = "$name $unit"
        override fun grams(formatted: String) = "$formatted 克"
        override fun minutes(minutes: Int) = "$minutes 分钟"
    }

    @Test
    fun `an English recipe reads as Markdown with every section and the calorie source`() {
        val recipe = Recipe(
            id = "a",
            name = "Cheese omelette",
            mealType = MealType.BREAKFAST,
            servings = 2,
            calories = Calories(420, 210, CalorieSource.DATABASE, 400),
            ingredients = listOf(
                Ingredient("Eggs", "egg", "3", "", 150.0, 210),
                Ingredient("Cheddar", "cheddar", "1/4", "cup", 28.35, 110),
                Ingredient("Salt", "salt", "", "to taste", null, null),
            ),
            tools = listOf("Non-stick pan", "Whisk"),
            steps = listOf(Step(1, "Whisk the eggs.", 2), Step(2, "Cook until set.", null)),
            language = Language.EN,
            setId = null,
            createdAt = 1,
            updatedAt = 1,
        )

        assertEquals(
            """
            # Cheese omelette

            Breakfast · 2 servings
            Database · 420 kcal total · 210 kcal per serving
            _Calories are approximate and not medical advice._

            ## Ingredients
            - 3 Eggs (150 g)
            - 1/4 cup Cheddar (28.4 g)
            - Salt, to taste

            ## Tools
            - Non-stick pan
            - Whisk

            ## Steps
            1. Whisk the eggs. (2 min)
            2. Cook until set.
            """.trimIndent(),
            shareText(recipe, English),
        )
    }

    @Test
    fun `a Chinese recipe uses Chinese labels and leaves out empty sections`() {
        val recipe = Recipe(
            id = "b",
            name = "番茄炒蛋",
            mealType = MealType.DINNER,
            servings = 1,
            calories = Calories(300, 300, CalorieSource.AI_ESTIMATE, 300),
            ingredients = listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 140), Ingredient("盐", "salt", "", "适量", null, null)),
            tools = emptyList(),
            steps = listOf(Step(1, "炒熟。", 5)),
            language = Language.ZH,
            setId = "set",
            createdAt = 1,
            updatedAt = 1,
        )

        assertEquals(
            """
            # 番茄炒蛋

            晚餐 · 1 人份
            ≈ AI 估算 · 共 300 千卡 · 每份 300 千卡
            _热量为估算值，不构成医疗建议。_

            ## 食材
            - 鸡蛋 2个 (100 克)
            - 盐 适量

            ## 步骤
            1. 炒熟。 (5 分钟)
            """.trimIndent(),
            shareText(recipe, Chinese),
        )
    }

    @Test
    fun `a recipe without a meal type or calories says the calories are not estimated`() {
        val recipe = Recipe(
            id = "c",
            name = "Tea",
            mealType = null,
            servings = 1,
            calories = Calories(null, null, CalorieSource.AI_ESTIMATE, null),
            ingredients = listOf(Ingredient("Tea", "tea", "1", "cup", null, null)),
            tools = emptyList(),
            steps = listOf(Step(1, "Brew.", null)),
            language = Language.EN,
            setId = null,
            createdAt = 1,
            updatedAt = 1,
        )

        val lines = shareText(recipe, English).lines()
        assertEquals("1 serving", lines[2])
        assertEquals("≈ AI estimate · Not estimated", lines[3])
    }
}
