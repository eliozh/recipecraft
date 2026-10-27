package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.InMemoryDataStore
import com.elio.recipecraft.testing.testRecipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSwitchCheckTest {

    private val settings = SettingsRepository(InMemoryDataStore())
    private val language = FakeLanguageSettings()
    private val repository = RecipeRepository(FakeRecipeDao(), FakeNutritionLookups(), UnconfinedTestDispatcher())
    private val check = LanguageSwitchCheck(settings, language, repository)

    private suspend fun saveEnglish(count: Int) =
        repository.save((1..count).map { testRecipe("en-$it", language = Language.EN) })

    @Test
    fun `the first check only stores the language`() = runTest {
        saveEnglish(2)
        language.resolved = Language.ZH

        assertNull(check.check())
        assertEquals(Language.ZH, settings.lastLanguage.first())
    }

    @Test
    fun `no change offers nothing`() = runTest {
        saveEnglish(2)
        settings.setLastLanguage(Language.EN)
        language.resolved = Language.EN

        assertNull(check.check())
    }

    @Test
    fun `a switch with recipes in the other language offers them and waits for an answer`() = runTest {
        saveEnglish(3)
        repository.save(listOf(testRecipe("zh-1", language = Language.ZH)))
        settings.setLastLanguage(Language.EN)
        language.resolved = Language.ZH

        val offer = check.check()

        assertEquals(TranslationOffer(count = 3, from = Language.EN, to = Language.ZH), offer)
        assertEquals("not stored until the user answers", Language.EN, settings.lastLanguage.first())
        assertEquals(offer, check.check())
    }

    @Test
    fun `a switch without recipes in the other language stores it and offers nothing`() = runTest {
        repository.save(listOf(testRecipe("zh-1", language = Language.ZH)))
        settings.setLastLanguage(Language.EN)
        language.resolved = Language.ZH

        assertNull(check.check())
        assertEquals(Language.ZH, settings.lastLanguage.first())
    }

    @Test
    fun `answering, such as with Not now, stores the new language so it does not ask again`() = runTest {
        saveEnglish(1)
        settings.setLastLanguage(Language.EN)
        language.resolved = Language.ZH

        check.answered(check.check()!!)

        assertEquals(Language.ZH, settings.lastLanguage.first())
        assertNull(check.check())
        // Switching back to English: no recipe is in Chinese, so there is nothing to offer.
        language.resolved = Language.EN
        assertNull(check.check())
    }
}
