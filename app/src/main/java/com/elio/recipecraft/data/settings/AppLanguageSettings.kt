package com.elio.recipecraft.data.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.LanguageResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The user's app language choice and the language the UI resolves to. */
interface AppLanguageSettings {
    val choice: AppLanguage

    /** The language the UI is shown in right now; prompts and translation follow it. */
    val resolvedLanguage: Language

    fun setChoice(choice: AppLanguage)
}

/**
 * Reads and applies the app language through the platform per-app locale API.
 * The system persists the per-app locale, so there is no local storage here.
 */
@Singleton
class LocaleManagerAppLanguageSettings @Inject constructor(
    @ApplicationContext context: Context,
) : AppLanguageSettings {

    private val localeManager = context.getSystemService(LocaleManager::class.java)

    override val choice: AppLanguage
        get() = LanguageResolver.choiceFromAppLocales(localeManager.applicationLocales.tags())

    override val resolvedLanguage: Language
        get() = LanguageResolver.resolve(choice, localeManager.systemLocales.tags())

    override fun setChoice(choice: AppLanguage) {
        localeManager.applicationLocales =
            LocaleList.forLanguageTags(LanguageResolver.appLocaleTagsFor(choice))
    }

    private fun LocaleList.tags(): List<String> = List(size()) { get(it).toLanguageTag() }
}
