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

/**
 * Reads and applies the app language through the platform per-app locale API.
 * The system persists the per-app locale, so there is no local storage here.
 */
@Singleton
class AppLanguageSettings @Inject constructor(@ApplicationContext context: Context) {

    private val localeManager = context.getSystemService(LocaleManager::class.java)

    val choice: AppLanguage
        get() = LanguageResolver.choiceFromAppLocales(localeManager.applicationLocales.tags())

    /** The language the UI is shown in right now; prompts and translation follow it. */
    val resolvedLanguage: Language
        get() = LanguageResolver.resolve(choice, localeManager.systemLocales.tags())

    fun setChoice(choice: AppLanguage) {
        localeManager.applicationLocales =
            LocaleList.forLanguageTags(LanguageResolver.appLocaleTagsFor(choice))
    }

    private fun LocaleList.tags(): List<String> = List(size()) { get(it).toLanguageTag() }
}
