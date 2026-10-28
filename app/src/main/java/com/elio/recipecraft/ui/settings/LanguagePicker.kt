package com.elio.recipecraft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.AppLanguage

@Composable
fun LanguagePicker(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle(R.string.settings_language)
        RadioGroup(
            options = AppLanguage.entries,
            selected = selected,
            label = { it.labelRes },
            onSelect = onSelect,
        )
    }
}

@get:StringRes
private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.FOLLOW_SYSTEM -> R.string.language_follow_system
        AppLanguage.EN -> R.string.language_english
        AppLanguage.ZH -> R.string.language_chinese
    }
