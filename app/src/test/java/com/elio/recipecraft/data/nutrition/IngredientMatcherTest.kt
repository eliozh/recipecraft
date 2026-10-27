package com.elio.recipecraft.data.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientMatcherTest {

    private val egg = NutritionFood("egg", listOf("whole egg", "chicken egg"), 143.0)
    private val eggplant = NutritionFood("eggplant", listOf("aubergine"), 25.0)
    private val tomato = NutritionFood("tomato", emptyList(), 18.0)
    private val strawberry = NutritionFood("strawberry", emptyList(), 32.0)
    private val bayLeaf = NutritionFood("bay leaf", emptyList(), 313.0)
    private val peach = NutritionFood("peach", emptyList(), 39.0)
    private val hummus = NutritionFood("hummus", emptyList(), 237.0)
    private val matcher = IngredientMatcher(listOf(egg, eggplant, tomato, strawberry, bayLeaf, peach, hummus))

    @Test
    fun `case, surrounding and inner whitespace are normalized`() {
        assertEquals(egg, matcher.match("  Egg "))
        assertEquals(egg, matcher.match("WHOLE \t  EGG"))
    }

    @Test
    fun `full-width characters and ideographic spaces fold to ascii`() {
        assertEquals(egg, matcher.match("ｅｇｇ"))
        assertEquals(egg, matcher.match("ｗｈｏｌｅ　ｅｇｇ"))
    }

    @Test
    fun `aliases match`() {
        assertEquals(egg, matcher.match("chicken egg"))
        assertEquals(eggplant, matcher.match("aubergine"))
    }

    @Test
    fun `simple english plurals are stripped`() {
        assertEquals(egg, matcher.match("eggs"))
        assertEquals(egg, matcher.match("whole eggs"))
        assertEquals(tomato, matcher.match("tomatoes"))
        assertEquals(strawberry, matcher.match("strawberries"))
        assertEquals(bayLeaf, matcher.match("bay leaves"))
        assertEquals(peach, matcher.match("peaches"))
        assertEquals(eggplant, matcher.match("eggplants"))
    }

    @Test
    fun `words that only look plural are not stripped`() {
        assertEquals(hummus, matcher.match("hummus"))
        assertNull(IngredientMatcher(listOf(NutritionFood("hummu", emptyList(), 1.0))).match("hummus"))
        assertNull(IngredientMatcher(listOf(NutritionFood("molasse", emptyList(), 1.0))).match("molasses"))
        assertNull(matcher.match("es"))
    }

    @Test
    fun `there are no partial matches`() {
        assertEquals(eggplant, matcher.match("eggplant"))
        assertNull(IngredientMatcher(listOf(egg)).match("eggplant"))
        assertNull(matcher.match("egg yolk"))
        assertNull(matcher.match("eg"))
        assertNull(matcher.match("tomato sauce"))
    }

    @Test
    fun `blank keys never match`() {
        assertNull(matcher.match(""))
        assertNull(matcher.match("   "))
        assertNull(IngredientMatcher(listOf(NutritionFood("", listOf(" "), 1.0))).match(""))
    }

    @Test
    fun `a name wins over another food's alias`() {
        val rice = NutritionFood("rice", emptyList(), 365.0)
        val brownRice = NutritionFood("brown rice", listOf("rice"), 367.0)
        assertEquals(rice, IngredientMatcher(listOf(brownRice, rice)).match("rice"))
    }

    @Test
    fun `normalize folds width, case and spaces`() {
        assertEquals("soy sauce", IngredientMatcher.normalize(" ＳＯＹ　 Sauce "))
    }
}
