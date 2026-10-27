package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptBuilderTest {

    /** The real bundled prompts; Gradle runs unit tests from the module directory. */
    private val assets = AssetReader { path -> File("src/main/assets/$path").readText() }
    private val language = FakeLanguageSettings()

    private fun builder(reader: AssetReader = assets) = PromptBuilder(reader, language, UnconfinedTestDispatcher())

    @Test
    fun `english picks the en templates and stamps EN`() = runTest {
        language.resolved = Language.EN
        val prompt = builder().chat(emptyList(), "Give me a breakfast recipe")

        assertEquals(Language.EN, prompt.language)
        val system = prompt.request.system!!
        assertTrue(system.contains("in English"))
        assertTrue(system.contains("\"nameEn\""))
        val user = prompt.request.messages.single()
        assertEquals(Role.USER, user.role)
        assertTrue(user.content.startsWith("Give me a breakfast recipe\n"))
        assertTrue(user.content.contains("```json"))
    }

    @Test
    fun `chinese picks the zh templates and stamps ZH`() = runTest {
        language.resolved = Language.ZH
        val prompt = builder().chat(emptyList(), "来一份早餐")

        assertEquals(Language.ZH, prompt.language)
        assertTrue(prompt.request.system!!.contains("都用简体中文书写"))
        assertTrue(prompt.request.messages.single().content.startsWith("来一份早餐\n"))
    }

    @Test
    fun `no placeholder is left in any filled prompt`() = runTest {
        for (lang in Language.entries) {
            language.resolved = lang
            val chat = builder().chat(emptyList(), "x")
            val repair = builder().repair("bad", "the JSON is not valid", lang)
            listOf(chat.request.system!!, chat.request.messages.single().content, repair.request.messages.single().content)
                .forEach { assertFalse("$lang: $it", it.contains("{{")) }
        }
    }

    @Test
    fun `history goes first, unchanged`() = runTest {
        val history = listOf(LlmMessage(Role.USER, "Hi"), LlmMessage(Role.ASSISTANT, "Hello"))
        val messages = builder().chat(history, "Breakfast?").request.messages
        assertEquals(history, messages.take(2))
        assertEquals(3, messages.size)
    }

    @Test
    fun `repair carries the bad reply and the problem in the original language`() = runTest {
        language.resolved = Language.EN
        val prompt = builder().repair("```json\n{oops", "the JSON is not valid", Language.ZH)

        assertEquals(Language.ZH, prompt.language)
        val user = prompt.request.messages.single().content
        assertTrue(user.contains("```json\n{oops"))
        assertTrue(user.contains("the JSON is not valid"))
        assertTrue(user.contains("无法解析"))
        assertTrue(prompt.request.system!!.contains("简体中文"))
    }

    @Test
    fun `templates are read from the language folder`() = runTest {
        val paths = mutableListOf<String>()
        language.resolved = Language.ZH
        builder { path -> paths += path; "" }.chat(emptyList(), "x")
        assertEquals(setOf("prompts/zh/generate.md", "prompts/zh/system.md"), paths.toSet())
    }

    @Test
    fun `every prompt exists in both languages`() {
        val en = File("src/main/assets/prompts/en").list()!!.toSet()
        val zh = File("src/main/assets/prompts/zh").list()!!.toSet()
        assertEquals(en, zh)
        assertTrue(en.containsAll(listOf("system.md", "generate.md", "repair.md")))
    }

    @Test
    fun `fill replaces in one pass and rejects a missing value`() {
        assertEquals("a {{b}} c", PromptBuilder.fill("a {{x}} c", mapOf("x" to "{{b}}", "unused" to "")))
        assertThrows(IllegalArgumentException::class.java) { PromptBuilder.fill("{{x}} {{y}}", mapOf("x" to "1")) }
    }
}
