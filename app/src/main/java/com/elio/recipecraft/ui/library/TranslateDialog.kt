package com.elio.recipecraft.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.data.recipe.TranslationBatchState
import com.elio.recipecraft.data.recipe.TranslationOffer
import com.elio.recipecraft.ui.common.translationErrorText
import com.elio.recipecraft.ui.recipe.label

/**
 * The app-wide translation dialogs: the offer after a language change, the batch's progress with
 * Cancel, and how it went. Hosted once, above the navigation, so it shows on any screen.
 */
@Composable
fun TranslationHost(viewModel: TranslationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    TranslationDialogs(
        state = state,
        onTranslate = viewModel::onTranslate,
        onNotNow = viewModel::onNotNow,
        onCancel = viewModel::onCancel,
        onResultShown = viewModel::onResultShown,
    )
}

@Composable
fun TranslationDialogs(
    state: TranslationUiState,
    onTranslate: () -> Unit,
    onNotNow: () -> Unit,
    onCancel: () -> Unit,
    onResultShown: () -> Unit,
) {
    val offer = state.offer
    when (val batch = state.batch) {
        is TranslationBatchState.Running -> ProgressDialog(batch, onCancel)
        is TranslationBatchState.Finished -> ResultDialog(batch, onResultShown)
        TranslationBatchState.Idle -> if (offer != null) OfferDialog(offer, onTranslate, onNotNow)
    }
}

@Composable
private fun OfferDialog(offer: TranslationOffer, onTranslate: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag(TranslationTestTags.OFFER),
        title = { Text(stringResource(R.string.translate_offer_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.translate_offer_message,
                    offer.count,
                    offer.count,
                    stringResource(offer.from.label),
                    stringResource(offer.to.label),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onTranslate, modifier = Modifier.testTag(TranslationTestTags.TRANSLATE)) {
                Text(stringResource(R.string.action_translate))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(TranslationTestTags.NOT_NOW)) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}

@Composable
private fun ProgressDialog(batch: TranslationBatchState.Running, onCancel: () -> Unit) {
    AlertDialog(
        // Only Cancel stops it, so a stray tap does not hide a batch that keeps running.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        modifier = Modifier.testTag(TranslationTestTags.PROGRESS),
        title = { Text(stringResource(R.string.translate_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (batch.total == 0) {
                    Text(stringResource(R.string.translate_starting))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(
                        stringResource(R.string.translate_progress, batch.current, batch.total),
                        modifier = Modifier.testTag(TranslationTestTags.PROGRESS_TEXT),
                    )
                    LinearProgressIndicator(
                        progress = { (batch.current - 1).toFloat() / batch.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(TranslationTestTags.CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ResultDialog(batch: TranslationBatchState.Finished, onResultShown: () -> Unit) {
    val lines = buildList {
        if (batch.translated > 0 || batch.cancelled) {
            add(pluralStringResource(R.plurals.translate_done_translated, batch.translated, batch.translated))
        }
        if (batch.failed > 0) add(pluralStringResource(R.plurals.translate_done_failed, batch.failed, batch.failed))
        batch.error?.let { add(translationErrorText(it)) }
        if (batch.cancelled) add(stringResource(R.string.translate_cancelled_rest))
        if (isEmpty()) add(stringResource(R.string.translate_none))
    }
    AlertDialog(
        onDismissRequest = onResultShown,
        modifier = Modifier.testTag(TranslationTestTags.RESULT),
        title = {
            Text(stringResource(if (batch.cancelled) R.string.translate_cancelled_title else R.string.translate_done_title))
        },
        text = { Text(lines.joinToString("\n"), modifier = Modifier.testTag(TranslationTestTags.RESULT_TEXT)) },
        confirmButton = {
            TextButton(onClick = onResultShown, modifier = Modifier.testTag(TranslationTestTags.OK)) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

/** Stable handles for UI tests. */
object TranslationTestTags {
    const val OFFER = "translation_offer"
    const val TRANSLATE = "translation_translate"
    const val NOT_NOW = "translation_not_now"
    const val PROGRESS = "translation_progress"
    const val PROGRESS_TEXT = "translation_progress_text"
    const val CANCEL = "translation_cancel"
    const val RESULT = "translation_result"
    const val RESULT_TEXT = "translation_result_text"
    const val OK = "translation_ok"
}
