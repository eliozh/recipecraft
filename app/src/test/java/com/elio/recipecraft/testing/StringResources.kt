package com.elio.recipecraft.testing

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One `<string>`, `<plurals>` or `<string-array>` of a strings.xml. */
data class StringResource(
    val type: String,
    val name: String,
    /** The text, trimmed; for plurals and arrays, every item's text joined by newlines. */
    val text: String,
    val translatable: Boolean,
    /** The `quantity` of each plurals item, empty for the other types. */
    val quantities: Set<String>,
)

/** Reads the string resources of `src/main/res/<dir>/strings.xml`, by type and name. */
fun stringResources(dir: String): Map<Pair<String, String>, StringResource> {
    val file = File("src/main/res/$dir/strings.xml")
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    return root.childElements()
        .filter { it.tagName in STRING_TYPES }
        .map { element ->
            val items = element.childElements().filter { it.tagName == "item" }
            StringResource(
                type = element.tagName,
                name = element.getAttribute("name"),
                text = if (element.tagName == "string") element.textContent.trim() else items.joinToString("\n") { it.textContent.trim() },
                translatable = element.getAttribute("translatable") != "false",
                quantities = items.mapNotNull { it.getAttribute("quantity").takeIf(String::isNotEmpty) }.toSet(),
            )
        }
        .associateBy { it.type to it.name }
}

private val STRING_TYPES = setOf("string", "plurals", "string-array")

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
