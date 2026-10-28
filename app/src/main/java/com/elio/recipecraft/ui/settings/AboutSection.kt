package com.elio.recipecraft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The USDA attribution shipped next to usda.db; shown in full from About. */
private const val NOTICE_ASSET = "nutrition/NOTICE"

/** Settings > About: reads the version from the installed package and the notice from assets. */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var showNotice by rememberSaveable { mutableStateOf(false) }
    About(versionName = versionName, onShowNotice = { showNotice = true }, modifier = modifier)
    if (showNotice) {
        val notice by produceState<String?>(initialValue = null, context) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(NOTICE_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            }
        }
        NoticeDialog(notice = notice, onDismiss = { showNotice = false })
    }
}

/**
 * The version, the calorie disclaimer, the USDA FoodData Central attribution and where user data
 * stays. [onShowNotice] opens the full NOTICE text.
 */
@Composable
fun About(versionName: String, onShowNotice: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle(R.string.settings_about)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AboutParagraph(R.string.recipe_calories_disclaimer)
            AboutParagraph(R.string.about_usda_attribution)
            AboutParagraph(R.string.about_imported_tables)
            AboutParagraph(R.string.about_api_keys)
        }
        TextButton(onClick = onShowNotice, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.about_show_notice))
        }
    }
}

@Composable
private fun AboutParagraph(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The full English NOTICE, as published with the data; [notice] is null while it loads. */
@Composable
private fun NoticeDialog(notice: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_notice_title)) },
        text = {
            Text(
                notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}
