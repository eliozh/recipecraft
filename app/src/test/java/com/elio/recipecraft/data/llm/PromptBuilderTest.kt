package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.testRecipe
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
            val generate = builder().generate(emptyList(), GenerationRequest(GenerationScope.FULL_DAY_SET, 2, "vegan"))
            val modify = builder().modify(testRecipe("r", language = lang), "vegan")
            listOf(
                modify.request.messages.single().content,
                chat.request.system!!,
                chat.request.messages.single().content,
                repair.request.messages.single().content,
                generate.request.messages.single().content,
            ).forEach { assertFalse("$lang: $it", it.contains("{{")) }
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
        assertEquals(setOf("prompts/zh/chat.md", "prompts/zh/system.md"), paths.toSet())

        paths.clear()
        language.resolved = Language.EN
        builder { path -> paths += path; "" }.generate(emptyList(), GenerationRequest(GenerationScope.LUNCH, 1))
        assertEquals(setOf("prompts/en/generate.md", "prompts/en/system.md"), paths.toSet())
    }

    @Test
    fun `a full-day request asks for sets numbered with set, in english`() = runTest {
        language.resolved = Language.EN
        val prompt = builder().generate(emptyList(), GenerationRequest(GenerationScope.FULL_DAY_SET, 2))

        assertEquals(Language.EN, prompt.language)
        val user = prompt.request.messages.single().content
        assertTrue(user.contains("- Scope: full_day_set\n"))
        assertTrue(user.contains("- Count: 2\n"))
        assertTrue(user.contains("- Recipes in total: 6\n"))
        assertTrue(user.contains("<<<>>>"))
        assertTrue(user.contains("\"set\""))
        assertTrue(prompt.request.system!!.contains("\"set\": integer"))
    }

    @Test
    fun `a single-meal request carries its meal, count and preferences, in chinese`() = runTest {
        language.resolved = Language.ZH
        val prompt = builder().generate(emptyList(), GenerationRequest(GenerationScope.DINNER, 3, "  素食，少油 "))

        assertEquals(Language.ZH, prompt.language)
        val user = prompt.request.messages.single().content
        assertTrue(user.contains("- 范围：dinner\n"))
        assertTrue(user.contains("- 数量：3\n"))
        assertTrue(user.contains("- 食谱总数：3\n"))
        assertTrue(user.contains("<<<素食，少油>>>"))
        assertTrue(prompt.request.system!!.contains("`set`"))
    }

    @Test
    fun `every scope and the count reach the prompt`() = runTest {
        for (lang in Language.entries) {
            language.resolved = lang
            for (scope in GenerationScope.entries) {
                for (count in listOf(1, 10)) {
                    val request = GenerationRequest(scope, count)
                    val user = builder().generate(emptyList(), request).request.messages.single().content
                    assertTrue("$lang $scope", user.contains(scope.name.lowercase()))
                    assertTrue("$lang $scope $count", Regex("\\b$count\\b").containsMatchIn(user))
                    assertTrue("$lang $scope total", user.contains("${request.expectedRecipes}\n"))
                }
            }
        }
    }

    @Test
    fun `a generate turn follows the history`() = runTest {
        val history = listOf(LlmMessage(Role.USER, "Hi"), LlmMessage(Role.ASSISTANT, "Hello"))
        val messages = builder().generate(history, GenerationRequest(GenerationScope.SNACK, 2)).request.messages
        assertEquals(history, messages.take(2))
        assertEquals(Role.USER, messages.last().role)
    }

    @Test
    fun `every prompt exists in both languages`() {
        val en = File("src/main/assets/prompts/en").list()!!.toSet()
        val zh = File("src/main/assets/prompts/zh").list()!!.toSet()
        assertEquals(en, zh)
        assertTrue(en.containsAll(listOf("system.md", "chat.md", "generate.md", "repair.md", "translate.md", "modify.md")))
    }

    @Test
    fun `translate fills the target language's template with the recipe json`() = runTest {
        language.resolved = Language.ZH // The app language does not pick the template; the target does.
        val prompt = builder().translate("{\"name\": \"Toast {{x}}\"}", Language.EN)

        assertEquals(Language.EN, prompt.language)
        assertEquals(null, prompt.request.system)
        val user = prompt.request.messages.single().content
        assertTrue(user.startsWith("Translate this recipe from the RecipeCraft app into English."))
        assertTrue(user.contains("<<<\n{\"name\": \"Toast {{x}}\"}\n>>>"))
        assertFalse(user.contains("{{"+"languageName}}"))
    }

    @Test
    fun `modify carries the recipe json and the instruction, in english`() = runTest {
        language.resolved = Language.ZH // The recipe's language picks the template, not the app language.
        val recipe = testRecipe("r-1", name = "Chicken wrap", mealType = MealType.LUNCH, setId = "set-1")
        val prompt = builder().modify(recipe, "  Make it vegetarian  ")

        assertEquals(Language.EN, prompt.language)
        assertTrue(prompt.request.system!!.contains("in English"))
        val user = prompt.request.messages.single().content
        assertTrue(user.startsWith("Change this recipe from the RecipeCraft app as the user asks."))
        assertTrue(user.contains("<<<\n${recipe.toWireJson()}\n>>>"))
        assertTrue(user.contains("\"name\":\"Chicken wrap\""))
        assertTrue(user.contains("\"mealType\":\"lunch\""))
        assertTrue(user.contains("<<<Make it vegetarian>>>"))
        // The app owns ids and sets; the model never sees them.
        assertFalse(user.contains("r-1"))
        assertFalse(user.contains("set-1"))
    }

    @Test
    fun `modify carries the recipe json and the instruction, in chinese`() = runTest {
        language.resolved = Language.EN
        val recipe = testRecipe("r-2", name = "番茄牛肉面", mealType = MealType.LUNCH, language = Language.ZH)
        val prompt = builder().modify(recipe, "改成素食")

        assertEquals(Language.ZH, prompt.language)
        assertTrue(prompt.request.system!!.contains("都用简体中文书写"))
        val user = prompt.request.messages.single().content
        assertTrue(user.startsWith("请按用户的要求修改这份来自食谱工坊应用的食谱。"))
        assertTrue(user.contains("<<<\n${recipe.toWireJson()}\n>>>"))
        assertTrue(user.contains("\"name\":\"番茄牛肉面\""))
        assertTrue(user.contains("<<<改成素食>>>"))
    }

    @Test
    fun `fill replaces in one pass and rejects a missing value`() {
        assertEquals("a {{b}} c", PromptBuilder.fill("a {{x}} c", mapOf("x" to "{{b}}", "unused" to "")))
        assertThrows(IllegalArgumentException::class.java) { PromptBuilder.fill("{{x}} {{y}}", mapOf("x" to "1")) }
    }
}
