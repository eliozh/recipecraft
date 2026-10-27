package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.Language
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** [count] saved recipes are in [from]; the app is now in [to]. */
data class TranslationOffer(val count: Int, val from: Language, val to: Language)

/**
 * Notices that the app language changed since saved recipes were last seen, by comparing the
 * resolved language with the one [SettingsRepository] stored.
 */
class LanguageSwitchCheck @Inject constructor(
    private val settings: SettingsRepository,
    private val languageSettings: AppLanguageSettings,
    private val repository: RecipeRepository,
) {

    /**
     * An offer to translate when the language changed and saved recipes are in the other
     * language. When there is nothing to offer, the current language is stored right away; when
     * there is, it is stored once the user answers, through [answered].
     *
     * The first check ever (nothing stored) only stores the language, since it cannot tell whether
     * the language changed.
     */
    suspend fun check(): TranslationOffer? {
        val current = languageSettings.resolvedLanguage
        val last = settings.lastLanguage.first()
        if (last == current) return null
        val others = if (last == null) emptyList() else repository.notIn(current)
        if (last == null || others.isEmpty()) {
            settings.setLastLanguage(current)
            return null
        }
        return TranslationOffer(others.size, from = last, to = current)
    }

    /** The user chose Translate or Not now for [offer]; stop offering until the language changes again. */
    suspend fun answered(offer: TranslationOffer) = settings.setLastLanguage(offer.to)
}
