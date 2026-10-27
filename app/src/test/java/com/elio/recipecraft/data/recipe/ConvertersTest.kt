package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `ingredients round trip, keeping nameEn, grams and missing numbers`() {
        val ingredients = listOf(
            Ingredient("鸡蛋", "egg", "2", "个", 100.0, 143),
            Ingredient("盐", "salt", "适量", "", null, null),
        )
        assertEquals(ingredients, converters.ingredientsFromJson(converters.ingredientsToJson(ingredients)))
    }

    @Test
    fun `the stored JSON uses stable keys`() {
        assertEquals(
            """[{"name":"鸡蛋","nameEn":"egg","quantity":"2","unit":"个","grams":100.0,"aiKcal":143}]""",
            converters.ingredientsToJson(listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 143))),
        )
        assertEquals(
            """{"total":300,"perServing":150,"source":"DATABASE","aiTotal":320}""",
            converters.caloriesToJson(Calories(300, 150, CalorieSource.DATABASE, 320)),
        )
    }

    @Test
    fun `steps, tools and calories round trip`() {
        val steps = listOf(Step(1, "Whisk", 2), Step(2, "Fry", null))
        assertEquals(steps, converters.stepsFromJson(converters.stepsToJson(steps)))

        val tools = listOf("pan", "锅铲")
        assertEquals(tools, converters.stringsFromJson(converters.stringsToJson(tools)))

        val calories = Calories(null, null, CalorieSource.AI_ESTIMATE, null)
        assertEquals(calories, converters.caloriesFromJson(converters.caloriesToJson(calories)))
    }

    @Test
    fun `unknown keys are ignored and missing optional numbers read as null`() {
        assertEquals(
            listOf(Ingredient("egg", "egg", "1", "", null, null)),
            converters.ingredientsFromJson("""[{"name":"egg","nameEn":"egg","quantity":"1","unit":"","extra":true}]"""),
        )
        assertEquals(listOf(Step(1, "Fry", null)), converters.stepsFromJson("""[{"index":1,"text":"Fry"}]"""))
        assertEquals(
            Calories(null, null, CalorieSource.AI_ESTIMATE, null),
            converters.caloriesFromJson("""{"source":"AI_ESTIMATE"}"""),
        )
    }
}
