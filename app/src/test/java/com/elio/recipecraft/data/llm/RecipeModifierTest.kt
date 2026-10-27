package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.MutableClock
import com.elio.recipecraft.testing.lookupOf
import java.io.File
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The modify_*.txt fixtures are synthetic: hand-written changes to the lunches of the full-day fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeModifierTest {

    private val client = FakeLlmClient()
    private val factory = FakeLlmClientFactory(LlmClientResult.Ready(client))
    private val language = FakeLanguageSettings()
    private val clock = MutableClock(Instant.ofEpochMilli(1_000))
    private val nutrition = FakeNutritionLookups()
    private val parser = RecipeParser()
    private val promptBuilder = PromptBuilder(
        AssetReader { path -> File("src/main/assets/$path").readText() },
        language,
        UnconfinedTestDispatcher(),
    )
    private var ids = 0
    private val assembler = RecipeAssembler(clock) { "id-${++ids}" }
    private val modifier = RecipeModifier(factory, promptBuilder, parser, assembler, nutrition, clock)

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    /** The lunch of the first set in a full-day fixture, as the chat shows it. */
    private fun lunch(fixture: String, language: Language): Recipe {
        val batch = (parser.parse(fixture(fixture)) as RecipeParseResult.Success).batch
        return assembler.assemble(batch, language, grouping = SetGrouping.FULL_DAY).recipes
            .first { it.mealType == MealType.LUNCH }
    }

    private fun reply(text: String) {
        client.events.trySend(LlmEvent.Delta(text))
        client.events.trySend(LlmEvent.Done(null))
    }

    private suspend fun modified(recipe: Recipe, instruction: String = "Make it vegetarian") =
        (modifier.modify(recipe, instruction) as ModifyResult.Success).recipe

    @Test
    fun `the change keeps the id, set, language and creation time and works out calories again`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        clock.instant = Instant.ofEpochMilli(5_000)
        nutrition.lookups = listOf(lookupOf("chickpea" to 164.0, "whole wheat tortilla" to 286.0))
        reply(fixture("modify_en_lunch_vegetarian.txt"))

        val changed = modified(wrap)

        assertEquals("Chickpea salad wrap", changed.name)
        assertEquals(listOf("chickpea", "whole wheat tortilla"), changed.ingredients.map { it.nameEn })
        assertEquals(wrap.id, changed.id)
        assertEquals(wrap.setId, changed.setId)
        assertEquals(Language.EN, changed.language)
        assertEquals(MealType.LUNCH, changed.mealType)
        assertEquals(wrap.createdAt, changed.createdAt)
        assertEquals(5_000L, changed.updatedAt)
        // 150 g of chickpeas at 164 and 50 g of tortilla at 286, from the tables rather than the AI's 389.
        assertEquals(CalorieSource.DATABASE, changed.calories.source)
        assertEquals(389, changed.calories.aiTotal)
        assertEquals(246 + 143, changed.calories.total)
        // One request, through modify.md with the recipe's JSON and the instruction.
        val user = client.requests.single().messages.single().content
        assertTrue(user.contains(wrap.toWireJson().toString()))
        assertTrue(user.contains("<<<Make it vegetarian>>>"))
    }

    @Test
    fun `a chinese recipe is changed in chinese and stays chinese`() = runTest {
        language.resolved = Language.EN // The app language has moved on; the recipe's language wins.
        val noodles = lunch("zh_full_day_x2.txt", Language.ZH)
        reply(fixture("modify_zh_lunch_vegetarian.txt"))

        val changed = modified(noodles, "改成素食")

        assertEquals("番茄豆腐面", changed.name)
        assertEquals(Language.ZH, changed.language)
        assertEquals(noodles.id, changed.id)
        assertEquals(noodles.setId, changed.setId)
        assertEquals(214, changed.calories.total)
        assertEquals(CalorieSource.AI_ESTIMATE, changed.calories.source)
        assertTrue(client.requests.single().messages.single().content.startsWith("请按用户的要求修改"))
    }

    @Test
    fun `a reply with more than one recipe gives the first`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        reply(fixture("modify_en_two_recipes.txt"))

        val changed = modified(wrap, "Fewer calories")

        assertEquals("Light chicken lettuce wrap", changed.name)
        assertEquals(wrap.id, changed.id)
    }

    @Test
    fun `the meal type stays the original's, so the set keeps its lunch`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        reply(fixture("modify_en_lunch_vegetarian.txt").replace("\"lunch\"", "\"dinner\""))

        assertEquals(MealType.LUNCH, modified(wrap).mealType)
    }

    @Test
    fun `an unreadable reply gets one repair turn`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        reply(fixture("modify_en_invalid.txt"))
        reply(fixture("modify_en_lunch_vegetarian.txt"))

        val changed = modified(wrap)

        assertEquals("Chickpea salad wrap", changed.name)
        assertEquals(2, client.requests.size)
        val repair = client.requests.last().messages.single().content
        assertTrue(repair.startsWith("Your previous reply could not be read as recipe JSON."))
        assertTrue(repair.contains("Here is the wrap without the chicken."))
    }

    @Test
    fun `a reply and repair without a recipe are rejected`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        reply("Sorry, I can't do that.")
        reply(fixture("modify_en_invalid.txt"))

        assertEquals(ModifyResult.Failure(ModifyError.Rejected), modifier.modify(wrap, "Make it vegetarian"))
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `a failed repair turn is rejected`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        reply(fixture("modify_en_invalid.txt"))
        client.events.trySend(LlmEvent.Error(LlmErrorKind.Network))

        assertEquals(ModifyResult.Failure(ModifyError.Rejected), modifier.modify(wrap, "Make it vegetarian"))
    }

    @Test
    fun `a failed request reports its kind`() = runTest {
        val wrap = lunch("en_full_day_x2.txt", Language.EN)
        client.events.trySend(LlmEvent.Delta("Here"))
        client.events.trySend(LlmEvent.Error(LlmErrorKind.RateLimited))

        assertEquals(
            ModifyResult.Failure(ModifyError.Llm(LlmErrorKind.RateLimited)),
            modifier.modify(wrap, "Make it vegetarian"),
        )
    }

    @Test
    fun `settings that cannot make a client report why without a request`() = runTest {
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        val wrap = lunch("en_full_day_x2.txt", Language.EN)

        assertEquals(
            ModifyResult.Failure(ModifyError.Llm(LlmErrorKind.MissingKey)),
            modifier.modify(wrap, "Make it vegetarian"),
        )
        assertTrue(client.requests.isEmpty())
    }
}
