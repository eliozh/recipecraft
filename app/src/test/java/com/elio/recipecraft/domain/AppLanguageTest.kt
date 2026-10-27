package com.elio.recipecraft.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    private fun followSystem(vararg tags: String) =
        LanguageResolver.resolve(AppLanguage.FOLLOW_SYSTEM, tags.toList())

    @Test
    fun `chinese system locales resolve to chinese`() {
        listOf("zh-CN", "zh-TW", "zh-HK", "zh-Hans-CN", "zh-Hant-TW", "zh").forEach {
            assertEquals(it, Language.ZH, followSystem(it))
        }
    }

    @Test
    fun `other system locales resolve to english`() {
        listOf("fr-FR", "fr", "ja-JP", "en-US", "de").forEach {
            assertEquals(it, Language.EN, followSystem(it))
        }
    }

    @Test
    fun `empty system locale list falls back to english`() {
        assertEquals(Language.EN, followSystem())
    }

    @Test
    fun `first supported locale in the list wins`() {
        assertEquals(Language.ZH, followSystem("fr-FR", "zh-CN", "en-US"))
        assertEquals(Language.EN, followSystem("ja-JP", "en-GB", "zh-CN"))
    }

    @Test
    fun `explicit choice overrides the system locale`() {
        assertEquals(Language.EN, LanguageResolver.resolve(AppLanguage.EN, listOf("zh-CN")))
        assertEquals(Language.ZH, LanguageResolver.resolve(AppLanguage.ZH, listOf("fr-FR")))
    }

    @Test
    fun `app locale list maps to the user's choice`() {
        assertEquals(AppLanguage.FOLLOW_SYSTEM, LanguageResolver.choiceFromAppLocales(emptyList()))
        assertEquals(AppLanguage.EN, LanguageResolver.choiceFromAppLocales(listOf("en")))
        assertEquals(AppLanguage.ZH, LanguageResolver.choiceFromAppLocales(listOf("zh-CN")))
        assertEquals(AppLanguage.FOLLOW_SYSTEM, LanguageResolver.choiceFromAppLocales(listOf("fr-FR")))
    }

    @Test
    fun `choice round-trips through app locale tags`() {
        AppLanguage.entries.forEach { choice ->
            val tags = LanguageResolver.appLocaleTagsFor(choice).split(',').filter { it.isNotEmpty() }
            assertEquals(choice, LanguageResolver.choiceFromAppLocales(tags))
        }
    }
}
