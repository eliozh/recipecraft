package com.elio.recipecraft.ui.recipe

import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.testing.ingredient
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeDiffTest {

    private fun lines(before: String, after: String, pair: Boolean = false) =
        diff(before.toList(), after.toList(), sameItem = { a, b -> a == b }, pairReplacements = pair)
            .joinToString(" ") { entry ->
                when (entry.change) {
                    Change.SAME -> "${entry.after}"
                    Change.ADDED -> "+${entry.after}"
                    Change.REMOVED -> "-${entry.before}"
                    Change.CHANGED -> "${entry.before}>${entry.after}"
                }
            }

    @Test
    fun `identical lists are all the same`() {
        assertEquals("a b c", lines("abc", "abc"))
    }

    @Test
    fun `lines are added and removed around the longest kept run`() {
        assertEquals("-a b c +d", lines("abc", "bcd"))
        assertEquals("a +x b -c", lines("abc", "axb"))
        assertEquals("+a +b", lines("", "ab"))
        assertEquals("-a -b", lines("ab", ""))
        assertEquals("", lines("", ""))
    }

    @Test
    fun `removed and added lines at the same place pair up as changed when asked`() {
        assertEquals("a b>x c", lines("abc", "axc", pair = true))
        assertEquals("a b>x +y", lines("ab", "axy", pair = true))
        assertEquals("a b>x -c", lines("abc", "ax", pair = true))
        assertEquals("a -b +x c", lines("abc", "axc"))
    }

    @Test
    fun `an ingredient with the same match key but a new amount is changed`() {
        val egg = ingredient("Eggs", "egg")
        val milk = ingredient("Milk", "milk")
        val chicken = ingredient("Chicken", "chicken breast")
        val chickpeas = ingredient("Chickpeas", "chickpea")
        val fewerEggs = egg.copy(quantity = "2", grams = 100.0)

        val entries = ingredientDiff(listOf(egg, chicken, milk), listOf(fewerEggs, chickpeas, milk.copy(name = "MILK")))

        assertEquals(
            listOf(
                DiffEntry(Change.CHANGED, egg, fewerEggs),
                DiffEntry(Change.REMOVED, chicken, null),
                DiffEntry(Change.ADDED, null, chickpeas),
                DiffEntry(Change.CHANGED, milk, milk.copy(name = "MILK")),
            ),
            entries,
        )
    }

    @Test
    fun `ingredients without a match key are lined up by name`() {
        val salt = ingredient("Salt", nameEn = " ")
        assertEquals(listOf(DiffEntry(Change.SAME, salt, salt)), ingredientDiff(listOf(salt), listOf(salt)))
    }

    @Test
    fun `steps are lined up by text, so a new step does not mark the renumbered ones`() {
        val before = listOf(Step(1, "Chop", 5), Step(2, "Fry", 10), Step(3, "Serve", null))
        val after = listOf(Step(1, "Chop", 5), Step(2, "Season", 1), Step(3, "Fry", 8), Step(4, "Plate it", null))

        assertEquals(
            listOf(
                DiffEntry(Change.SAME, before[0], after[0]),
                DiffEntry(Change.ADDED, null, after[1]),
                DiffEntry(Change.CHANGED, before[1], after[2]),
                DiffEntry(Change.CHANGED, before[2], after[3]),
            ),
            stepDiff(before, after),
        )
    }
}
