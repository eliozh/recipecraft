package com.elio.recipecraft.data.recipe

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.elio.recipecraft.data.llm.AssetReader
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.PromptBuilder
import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.recipe.TranslationBatchState.Finished
import com.elio.recipecraft.data.recipe.TranslationBatchState.Running
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
import com.elio.recipecraft.testing.translatedReply
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeTranslationsTest {

    private val dao = FakeRecipeDao()
    private val repository = RecipeRepository(dao, FakeNutritionLookups(listOf(lookupOf("egg" to 143.0))), UnconfinedTestDispatcher())
    private val client = FakeLlmClient()
    private val factory = FakeLlmClientFactory(LlmClientResult.Ready(client))
    private val translator = RecipeTranslator(
        PromptBuilder(AssetReader { File("src/main/assets/$it").readText() }, FakeLanguageSettings(), UnconfinedTestDispatcher()),
        RecipeParser(),
        Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC),
    )

    /** Three English recipes in one set, newest first, so the batch takes them as a, b, c. */
    private val english = listOf("a" to 3L, "b" to 2L, "c" to 1L).map { (id, createdAt) ->
        testRecipe(id, ingredients = listOf(ingredient("egg", grams = 50.0)), setId = "set-1", createdAt = createdAt)
    }

    private fun TestScope.translations() = RecipeTranslations(factory, translator, repository, backgroundScope)

    private suspend fun saveEnglish(): List<Recipe> {
        repository.save(english)
        return dao.recipes.map { it.toDomain() }
    }

    private fun saved(id: String) = dao.recipes.single { it.id == id }.toDomain()

    private fun reply(text: String) {
        client.events.trySend(LlmEvent.Delta(text))
        client.events.trySend(LlmEvent.Done(null))
    }

    /** Skips states until [expected]; a StateFlow may skip ones that are replaced at once. */
    private suspend fun ReceiveTurbine<TranslationBatchState>.awaitState(expected: TranslationBatchState) {
        while (true) if (awaitItem() == expected) return
    }

    @Test
    fun `the batch translates every recipe with progress and keeps ids, sets, grams and calories`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        assertEquals(CalorieSource.DATABASE, before.first().calories.source)
        val translations = translations()

        translations.batch.test {
            assertEquals(TranslationBatchState.Idle, awaitItem())
            translations.startBatch(Language.ZH)
            awaitState(Running(1, 3))
            reply(translatedReply(english[0]))
            awaitState(Running(2, 3))
            reply(translatedReply(english[1]))
            awaitState(Running(3, 3))
            reply(translatedReply(english[2]))
            awaitState(Finished(translated = 3, failed = 0, cancelled = false, error = null))
        }

        before.forEach { original ->
            val after = saved(original.id)
            assertEquals(Language.ZH, after.language)
            assertEquals("译 ${original.name}", after.name)
            assertEquals(original.setId, after.setId)
            assertEquals(original.ingredients.map { it.grams }, after.ingredients.map { it.grams })
            assertEquals(original.calories, after.calories)
            assertEquals(9_000L, after.updatedAt)
        }
        assertEquals(3, client.requests.size)
    }

    @Test
    fun `a rejected translation in the middle leaves that recipe as it was and carries on`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        reply(translatedReply(english[0]))
        reply("I would rather not.")
        reply(translatedReply(english[2]))

        translations.startBatch(Language.ZH)

        assertEquals(Finished(translated = 2, failed = 1, cancelled = false, error = TranslationError.Rejected), translations.batch.value)
        assertEquals(before[1], saved("b"))
        assertEquals(Language.ZH, saved("c").language)
    }

    @Test
    fun `a connection error stops the batch and leaves the rest untouched`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        reply(translatedReply(english[0]))
        client.events.trySend(LlmEvent.Error(LlmErrorKind.Network))

        translations.startBatch(Language.ZH)

        assertEquals(Finished(1, 2, cancelled = false, error = TranslationError.Llm(LlmErrorKind.Network)), translations.batch.value)
        assertEquals(before.drop(1), listOf(saved("b"), saved("c")))
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `cancelling mid-batch keeps the recipes already translated and leaves the rest untouched`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()

        translations.batch.test {
            translations.startBatch(Language.ZH)
            awaitState(Running(1, 3))
            reply(translatedReply(english[0]))
            awaitState(Running(2, 3))
            translations.cancelBatch()
            awaitState(Finished(translated = 1, failed = 0, cancelled = true, error = null))
        }

        assertTrue(client.cancelled)
        assertEquals(Language.ZH, saved("a").language)
        assertEquals(before.drop(1), listOf(saved("b"), saved("c")))

        translations.dismissBatch()
        assertEquals(TranslationBatchState.Idle, translations.batch.value)
    }

    @Test
    fun `without a usable client every recipe fails and nothing is sent`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        val translations = translations()

        translations.startBatch(Language.ZH)

        assertEquals(Finished(0, 3, cancelled = false, error = TranslationError.Llm(LlmErrorKind.MissingKey)), translations.batch.value)
        assertEquals(before, dao.recipes.map { it.toDomain() })
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `no recipe in the other language finishes at once`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(listOf(testRecipe("zh", language = Language.ZH)))
        val translations = translations()

        translations.startBatch(Language.ZH)

        assertEquals(Finished(0, 0, cancelled = false, error = null), translations.batch.value)
    }

    @Test
    fun `starting again while a batch runs is ignored`() = runTest(UnconfinedTestDispatcher()) {
        saveEnglish()
        val translations = translations()
        translations.startBatch(Language.ZH)
        translations.startBatch(Language.ZH)
        translations.dismissBatch() // Not finished, so it stays.

        assertEquals(Running(1, 3), translations.batch.value)
        assertEquals(1, client.requests.size)
        translations.cancelBatch()
    }

    @Test
    fun `an unreadable or unwritable database is reported`() = runTest(UnconfinedTestDispatcher()) {
        saveEnglish()
        val translations = translations()
        dao.failReads = true
        translations.startBatch(Language.ZH)
        assertEquals(Finished(0, 0, cancelled = false, error = TranslationError.Storage), translations.batch.value)

        dao.failReads = false
        dao.failWrites = true
        english.forEach { reply(translatedReply(it)) }
        translations.startBatch(Language.ZH)
        assertEquals(Finished(0, 3, cancelled = false, error = TranslationError.Storage), translations.batch.value)
    }

    @Test
    fun `the batch shows a rate-limit wait on the recipe it is translating`() = runTest(UnconfinedTestDispatcher()) {
        saveEnglish()
        val translations = translations()
        client.events.trySend(LlmEvent.Retrying(1, 3_000))

        translations.startBatch(Language.ZH)
        assertEquals(Running(1, 3, retryingInSeconds = 3), translations.batch.value)

        client.events.trySend(LlmEvent.Error(LlmErrorKind.RateLimited))
        assertEquals(Finished(0, 3, cancelled = false, error = TranslationError.Llm(LlmErrorKind.RateLimited)), translations.batch.value)
    }

    @Test
    fun `one recipe reports a rate-limit wait, then clears it when the reply starts`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        client.events.trySend(LlmEvent.Retrying(1, 2_000))
        reply(translatedReply(english[1]))
        val waits = mutableListOf<Int?>()

        assertNull(translations.translate(before[1], Language.ZH) { waits += it })
        assertEquals(listOf(2, null), waits)
    }

    @Test
    fun `one recipe is translated and saved in place`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        reply(translatedReply(english[1]))

        assertNull(translations.translate(before[1], Language.ZH))

        assertEquals(Language.ZH, saved("b").language)
        assertEquals(before[1].id, saved("b").id)
        assertEquals(before[0], saved("a"))
    }

    @Test
    fun `one recipe already in the language, or without a client, is left alone`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        assertNull(translations.translate(before[0], Language.EN))

        factory.result = LlmClientResult.Unavailable(LlmErrorKind.InvalidKey)
        assertEquals(TranslationError.Llm(LlmErrorKind.InvalidKey), translations.translate(before[0], Language.ZH))
        assertEquals(before, dao.recipes.map { it.toDomain() })
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `a recipe deleted while it was translated stays deleted`() = runTest(UnconfinedTestDispatcher()) {
        val before = saveEnglish()
        val translations = translations()
        repository.delete("a")
        reply(translatedReply(english[0]))

        assertNull(translations.translate(before[0], Language.ZH))

        assertEquals(listOf("b", "c"), dao.recipes.map { it.id })
    }
}
