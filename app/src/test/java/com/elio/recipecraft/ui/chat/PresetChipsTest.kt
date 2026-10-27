package com.elio.recipecraft.ui.chat

import com.elio.recipecraft.R
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PresetChipsTest {

    /** Resolves a string resource id the way the app does, from the strings.xml in [folder]. */
    private class Strings(folder: String) : (Int) -> String {
        private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        private val values: Map<String, String> = run {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$folder/strings.xml"))
            val nodes = document.getElementsByTagName("string")
            (0 until nodes.length).associate { i ->
                val element = nodes.item(i) as Element
                element.getAttribute("name") to unescape(element.textContent)
            }
        }

        override fun invoke(id: Int): String = values.getValue(names.getValue(id))

        /** Android's quoting: surrounding quotes keep spaces, and a backslash escapes the next character. */
        private fun unescape(raw: String): String =
            raw.removeSurrounding("\"").replace(Regex("\\\\(.)"), "$1")
    }

    private val en = Strings("values")
    private val zh = Strings("values-zh")

    @Test
    fun `starter chips show on an empty chat and follow-ups come first once there are messages`() {
        assertEquals(
            listOf(PresetChip.FULL_DAY_PLAN, PresetChip.QUICK_BREAKFASTS, PresetChip.VEGETARIAN_DAY, PresetChip.FRIDGE),
            PresetChip.forChat(empty = true),
        )
        assertEquals(
            listOf(PresetChip.MAKE_IT_VEGETARIAN, PresetChip.UNDER_500_KCAL) + PresetChip.forChat(empty = true),
            PresetChip.forChat(empty = false),
        )
    }

    @Test
    fun `each chip maps to its template in english`() {
        assertEquals("Full day plan", en(PresetChip.FULL_DAY_PLAN.label))
        assertEquals(PresetAction.Generate(GenerationRequest(GenerationScope.FULL_DAY_SET, 1)), PresetChip.FULL_DAY_PLAN.action(en))
        assertEquals(
            PresetAction.Generate(GenerationRequest(GenerationScope.BREAKFAST, 3, "quick, ready in 15 minutes")),
            PresetChip.QUICK_BREAKFASTS.action(en),
        )
        assertEquals(
            PresetAction.Generate(GenerationRequest(GenerationScope.FULL_DAY_SET, 1, "vegetarian")),
            PresetChip.VEGETARIAN_DAY.action(en),
        )
        assertEquals("Use what's in my fridge…", en(PresetChip.FRIDGE.label))
        assertEquals(PresetAction.Fill("Suggest a recipe using what's in my fridge: "), PresetChip.FRIDGE.action(en))
        assertEquals(PresetAction.Send("Make the recipes above vegetarian."), PresetChip.MAKE_IT_VEGETARIAN.action(en))
        assertEquals(
            PresetAction.Send("Change the recipes above so each is under 500 kcal per serving."),
            PresetChip.UNDER_500_KCAL.action(en),
        )
    }

    @Test
    fun `each chip maps to the same template in chinese`() {
        assertEquals("全天食谱", zh(PresetChip.FULL_DAY_PLAN.label))
        assertEquals(PresetAction.Generate(GenerationRequest(GenerationScope.FULL_DAY_SET, 1)), PresetChip.FULL_DAY_PLAN.action(zh))
        assertEquals(
            PresetAction.Generate(GenerationRequest(GenerationScope.BREAKFAST, 3, "快手，15 分钟内做好")),
            PresetChip.QUICK_BREAKFASTS.action(zh),
        )
        assertEquals(
            PresetAction.Generate(GenerationRequest(GenerationScope.FULL_DAY_SET, 1, "素食")),
            PresetChip.VEGETARIAN_DAY.action(zh),
        )
        assertEquals(PresetAction.Fill("用我冰箱里的这些食材推荐一道菜："), PresetChip.FRIDGE.action(zh))
        assertEquals(PresetAction.Send("把上面的食谱改成素食。"), PresetChip.MAKE_IT_VEGETARIAN.action(zh))
        assertEquals(PresetAction.Send("调整上面的食谱，让每道每份不超过 500 千卡。"), PresetChip.UNDER_500_KCAL.action(zh))
    }

    @Test
    fun `every chip has its label and template text in both languages`() {
        PresetChip.entries.forEach { chip ->
            val ids = listOf(chip.label) + when (val t = chip.template) {
                is PresetTemplate.Generate -> listOfNotNull(t.preferences)
                is PresetTemplate.Send -> listOf(t.text)
                is PresetTemplate.Fill -> listOf(t.text)
            }
            ids.forEach { id ->
                assertTrue("$chip en", en(id).isNotBlank())
                assertTrue("$chip zh", zh(id).isNotBlank())
                assertTrue("$chip is not translated", en(id) != zh(id))
            }
        }
    }
}
