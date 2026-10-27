package com.elio.recipecraft.domain

/** The language the user picked in Settings. */
enum class AppLanguage { FOLLOW_SYSTEM, EN, ZH }

/** A language the UI and prompts can actually be in. */
enum class Language { EN, ZH }

object LanguageResolver {

    /**
     * Resolves [choice] against the system locale list (BCP 47 tags, in preference order).
     *
     * Mirrors Android's resource resolution: the first system locale the app supports wins,
     * English counts as supported because it lives in the default resources, and a list with
     * no supported locale falls back to those English defaults.
     */
    fun resolve(choice: AppLanguage, systemLocaleTags: List<String>): Language = when (choice) {
        AppLanguage.EN -> Language.EN
        AppLanguage.ZH -> Language.ZH
        AppLanguage.FOLLOW_SYSTEM -> systemLocaleTags.firstNotNullOfOrNull(::languageOf) ?: Language.EN
    }

    /** The user's choice implied by the per-app locale list; an empty list means follow system. */
    fun choiceFromAppLocales(appLocaleTags: List<String>): AppLanguage =
        when (appLocaleTags.firstOrNull()?.let(::languageOf)) {
            null -> AppLanguage.FOLLOW_SYSTEM
            Language.EN -> AppLanguage.EN
            Language.ZH -> AppLanguage.ZH
        }

    /** The per-app locale tags that apply [choice]; empty hands the decision back to the system. */
    fun appLocaleTagsFor(choice: AppLanguage): String = when (choice) {
        AppLanguage.FOLLOW_SYSTEM -> ""
        AppLanguage.EN -> "en"
        AppLanguage.ZH -> "zh"
    }

    private fun languageOf(tag: String): Language? =
        when (tag.substringBefore('-').substringBefore('_').lowercase()) {
            "en" -> Language.EN
            "zh" -> Language.ZH
            else -> null
        }
}
