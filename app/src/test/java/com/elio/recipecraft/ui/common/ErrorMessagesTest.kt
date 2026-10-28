package com.elio.recipecraft.ui.common

import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.nutrition.RowError
import com.elio.recipecraft.data.nutrition.TableImportError
import com.elio.recipecraft.data.recipe.RecipeImportError
import com.elio.recipecraft.testing.stringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every error maps to a message that exists, and is not blank, in English and in Chinese. */
class ErrorMessagesTest {

    /** The `<string>` and `<plurals>` texts of one strings.xml, by type and name. */
    private fun resources(dir: String): Map<Pair<String, String>, String> =
        stringResources(dir).filterKeys { it.first == "string" || it.first == "plurals" }.mapValues { it.value.text }

    private val english = resources("values")
    private val chinese = resources("values-zh")

    private fun names(type: Class<*>) = type.fields.associate { it.getInt(null) to it.name }

    private val stringNames = names(R.string::class.java)
    private val pluralNames = names(R.plurals::class.java)

    private fun assertBothLanguages(type: String, name: String) {
        for ((language, texts) in listOf("values" to english, "values-zh" to chinese)) {
            val text = texts[type to name]
            assertTrue("$type $name is missing from $language", text != null)
            assertFalse("$type $name is blank in $language", text!!.isBlank())
        }
    }

    private fun assertString(id: Int) = assertBothLanguages("string", stringNames.getValue(id))

    private val llmKinds: List<LlmErrorKind> = listOf(
        LlmErrorKind.InvalidKey,
        LlmErrorKind.RateLimited,
        LlmErrorKind.Network,
        LlmErrorKind.Timeout,
        LlmErrorKind.Server(503),
        LlmErrorKind.MalformedResponse,
        LlmErrorKind.MissingKey,
        LlmErrorKind.MissingModel,
        LlmErrorKind.InvalidEndpoint,
        LlmErrorKind.KeyStoreUnavailable,
        LlmErrorKind.SettingsUnavailable,
    )

    /** Exhaustive, so a new kind fails to compile here until it is added to [llmKinds]. */
    private fun listed(kind: LlmErrorKind): Boolean = when (kind) {
        LlmErrorKind.InvalidKey, LlmErrorKind.RateLimited, LlmErrorKind.Network, LlmErrorKind.Timeout,
        is LlmErrorKind.Server, LlmErrorKind.MalformedResponse, LlmErrorKind.MissingKey, LlmErrorKind.MissingModel,
        LlmErrorKind.InvalidEndpoint, LlmErrorKind.KeyStoreUnavailable, LlmErrorKind.SettingsUnavailable,
        -> llmKinds.any { it::class == kind::class }
    }

    @Test
    fun `the list covers every llm error kind`() {
        assertEquals(11, llmKinds.map { it::class }.distinct().size)
        assertTrue(llmKinds.all(::listed))
    }

    @Test
    fun `every llm error kind has a message in both languages, in and out of settings`() {
        for (kind in llmKinds) {
            assertString(ErrorMessages.llm(kind))
            assertString(ErrorMessages.llm(kind, inSettings = true))
        }
        assertString(ErrorMessages.retrying)
    }

    @Test
    fun `the settings wording never sends the user to settings`() {
        for (kind in llmKinds) {
            val name = stringNames.getValue(ErrorMessages.llm(kind, inSettings = true))
            assertFalse("$name points at Settings", english.getValue("string" to name).contains("in Settings"))
            assertFalse("$name points at Settings", chinese.getValue("string" to name).contains("在设置中"))
        }
        // Errors fixed in Settings say so outside it, and have their own wording inside.
        for (kind in llmKinds.filter { it.isFixedInSettings }) {
            assertNotEquals(ErrorMessages.llm(kind), ErrorMessages.llm(kind, inSettings = true))
        }
    }

    @Test
    fun `feature errors have messages in both languages`() {
        listOf(TranslationError.Rejected, TranslationError.Storage, TranslationError.Llm(LlmErrorKind.Timeout))
            .forEach { assertString(ErrorMessages.translation(it)) }
        assertEquals(ErrorMessages.llm(LlmErrorKind.Timeout), ErrorMessages.translation(TranslationError.Llm(LlmErrorKind.Timeout)))
        listOf(ModifyError.Rejected, ModifyError.Llm(LlmErrorKind.MissingKey)).forEach { assertString(ErrorMessages.modify(it)) }
        assertEquals(ErrorMessages.llm(LlmErrorKind.MissingKey), ErrorMessages.modify(ModifyError.Llm(LlmErrorKind.MissingKey)))
        TableImportError.entries.forEach { assertString(ErrorMessages.tableImport(it)) }
        RowError.entries.forEach { assertString(ErrorMessages.row(it)) }
        RecipeImportError.entries.forEach { assertString(ErrorMessages.recipeImport(it)) }
        AppError.entries.forEach { assertString(it.message) }
        listOf(true, false).forEach { assertBothLanguages("plurals", pluralNames.getValue(ErrorMessages.shortfall(it))) }
        assertString(ErrorMessages.incompleteSet)
    }

    @Test
    fun `format arguments match between the languages`() {
        val placeholder = Regex("%(\\d+\\$)?[sd]")
        for (key in english.keys) {
            assertEquals(
                "placeholders of ${key.second}",
                placeholder.findAll(english.getValue(key)).map { it.value }.toSet(),
                placeholder.findAll(chinese.getValue(key)).map { it.value }.toSet(),
            )
        }
    }
}
