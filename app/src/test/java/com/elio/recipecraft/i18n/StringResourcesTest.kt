package com.elio.recipecraft.i18n

import com.elio.recipecraft.testing.stringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every user-facing string exists in English and in Chinese, under the same names. */
class StringResourcesTest {

    private val english = stringResources("values")
    private val chinese = stringResources("values-zh")

    private fun Map<Pair<String, String>, *>.names(type: String) = keys.filter { it.first == type }.map { it.second }.toSet()

    @Test
    fun `both languages have the same strings, plurals and string arrays`() {
        val translated = english.filterValues { it.translatable }
        for (type in listOf("string", "plurals", "string-array")) {
            assertEquals("$type names", translated.names(type), chinese.names(type))
        }
    }

    @Test
    fun `strings marked not translatable exist only in the default language`() {
        val fixed = english.values.filterNot { it.translatable }.map { it.type to it.name }.toSet()
        assertEquals(emptySet<Pair<String, String>>(), fixed intersect chinese.keys)
        assertEquals(emptyList<String>(), chinese.values.filterNot { it.translatable }.map { it.name })
    }

    @Test
    fun `no string is blank and every plurals has an other quantity`() {
        for ((language, resources) in listOf("values" to english, "values-zh" to chinese)) {
            resources.values.forEach { resource ->
                assertTrue("${resource.type} ${resource.name} is blank in $language", resource.text.isNotBlank())
                if (resource.type == "plurals") {
                    assertTrue("plurals ${resource.name} has no other in $language", "other" in resource.quantities)
                }
            }
        }
    }
}
