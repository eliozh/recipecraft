package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.nutrition.CalorieCalculator
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.oatmealUsdaLookup
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures are synthetic: hand-written translations of en_breakfast.txt and zh_tomato_egg.txt. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeTranslatorTest {

    private val assets = AssetReader { path -> File("src/main/assets/$path").readText() }
    private val promptBuilder = PromptBuilder(assets, FakeLanguageSettings(), UnconfinedTestDispatcher())
    private val parser = RecipeParser()
    private val translator = RecipeTranslator(promptBuilder, parser, Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC))
    private val client = FakeLlmClient()

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private fun saved(fixture: String, language: Language): Recipe {
        val batch = (parser.parse(fixture(fixture)) as RecipeParseResult.Success).batch
        var id = 0
        val assembler = RecipeAssembler(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)) { "id-${++id}" }
        return assembler.assemble(batch, language, listOf(oatmealUsdaLookup())).recipes.single().copy(setId = "set-1")
    }

    private fun reply(text: String) {
        client.events.trySend(LlmEvent.Delta(text))
        client.events.trySend(LlmEvent.Done(null))
    }

    @Test
    fun `english to chinese keeps every number and key and recalculates the same calories`() = runTest {
        val oatmeal = saved("en_breakfast.txt", Language.EN)
        reply(fixture("translate_en_to_zh.txt"))

        val translated = (translator.translate(oatmeal, Language.ZH, client) as TranslationResult.Success).recipe

        assertEquals("香蕉牛奶燕麦粥", translated.name)
        assertEquals(Language.ZH, translated.language)
        assertEquals(listOf("燕麦片", "全脂牛奶", "香蕉", "蜂蜜", "肉桂粉"), translated.ingredients.map { it.name })
        assertEquals(listOf("小奶锅", "木勺", "刀", "砧板"), translated.tools)
        assertEquals(oatmeal.id, translated.id)
        assertEquals(oatmeal.setId, translated.setId)
        assertEquals(oatmeal.ingredients.map { it.grams }, translated.ingredients.map { it.grams })
        assertEquals(oatmeal.ingredients.map { it.nameEn }, translated.ingredients.map { it.nameEn })
        assertEquals(oatmeal.steps.map { it.minutes }, translated.steps.map { it.minutes })
        assertEquals(oatmeal.calories, translated.calories)
        assertEquals(9_000L, translated.updatedAt)
        // USDA matches on nameEn, which a translation keeps, so recalculating gives the same figure.
        assertEquals(CalorieSource.DATABASE, oatmeal.calories.source)
        assertEquals(oatmeal.calories, CalorieCalculator.calculate(translated, listOf(oatmealUsdaLookup())))
    }

    @Test
    fun `chinese to english reads a fenced reply with prose`() = runTest {
        val tomatoEgg = saved("zh_tomato_egg.txt", Language.ZH)
        reply(fixture("translate_zh_to_en.txt"))

        val translated = (translator.translate(tomatoEgg, Language.EN, client) as TranslationResult.Success).recipe

        assertEquals("Stir-fried tomato and egg", translated.name)
        assertEquals(Language.EN, translated.language)
        assertEquals(listOf("", "", "tbsp", "g"), translated.ingredients.map { it.unit })
        assertEquals(tomatoEgg.ingredients.map { it.grams }, translated.ingredients.map { it.grams })
        assertTrue(RecipeTranslation.isTextOnlyChange(tomatoEgg, translated))
    }

    @Test
    fun `the request sends the recipe json through the target language's template`() = runTest {
        val oatmeal = saved("en_breakfast.txt", Language.EN)
        reply(fixture("translate_en_to_zh.txt"))
        translator.translate(oatmeal, Language.ZH, client)

        val request = client.requests.single()
        assertNull(request.system)
        val user = request.messages.single().content
        assertTrue(user.startsWith("请把这份来自食谱工坊应用的食谱翻译成简体中文"))
        val json = Json.parseToJsonElement(user.substringAfter("<<<\n").substringBefore("\n>>>")).jsonObject
        assertEquals("Banana oatmeal with milk", json["name"]!!.jsonPrimitive.content)
        assertEquals("breakfast", json["mealType"]!!.jsonPrimitive.content)
        assertEquals(5, json["ingredients"]!!.jsonArray.size)
        assertEquals("rolled oats", json["ingredients"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)
        assertFalse("ids stay in the app", "id-" in user)
    }

    @Test
    fun `a failed request is an llm error`() = runTest {
        client.events.trySend(LlmEvent.Delta("{"))
        client.events.trySend(LlmEvent.Error(LlmErrorKind.Network))

        val result = translator.translate(saved("en_breakfast.txt", Language.EN), Language.ZH, client)

        assertEquals(TranslationResult.Failure(TranslationError.Llm(LlmErrorKind.Network)), result)
    }

    @Test
    fun `a prose reply or a reply that changed the structure is rejected`() = runTest {
        val oatmeal = saved("en_breakfast.txt", Language.EN)
        reply("Sorry, I can't translate that.")
        assertEquals(TranslationResult.Failure(TranslationError.Rejected), translator.translate(oatmeal, Language.ZH, client))

        reply(fixture("translate_en_to_zh.txt").replace("\"grams\": 80", "\"grams\": 90"))
        assertEquals(TranslationResult.Failure(TranslationError.Rejected), translator.translate(oatmeal, Language.ZH, client))

        reply("""{"recipes": [${fixture("translate_en_to_zh.txt")}, ${fixture("translate_en_to_zh.txt")}]}""")
        assertEquals(TranslationResult.Failure(TranslationError.Rejected), translator.translate(oatmeal, Language.ZH, client))
    }
}
