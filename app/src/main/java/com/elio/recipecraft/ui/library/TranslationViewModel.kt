package com.elio.recipecraft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.recipe.LanguageSwitchCheck
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.recipe.TranslationBatchState
import com.elio.recipecraft.data.recipe.TranslationOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranslationUiState(
    /** Shown as the "Translate saved recipes?" dialog until the user answers. */
    val offer: TranslationOffer? = null,
    val batch: TranslationBatchState = TranslationBatchState.Idle,
)

/**
 * Offers to translate saved recipes after the app language changed, and follows the batch. It
 * lives at the activity level, so the offer and the progress survive rotation; the batch itself
 * runs in [RecipeTranslations] on the app scope.
 */
@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val check: LanguageSwitchCheck,
    private val translations: RecipeTranslations,
) : ViewModel() {

    private val offer = MutableStateFlow<TranslationOffer?>(null)
    private var checking: Job? = null

    val uiState: StateFlow<TranslationUiState> = combine(offer, translations.batch, ::TranslationUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TranslationUiState(batch = translations.batch.value))

    /**
     * At start and on every resume, since the language can change in system Settings meanwhile.
     * An offer on screen is checked again too, so switching back, or on to another language,
     * replaces or closes it rather than offering the wrong language.
     */
    fun onResume() {
        if (checking?.isActive == true) return
        if (translations.batch.value is TranslationBatchState.Running) return
        checking = viewModelScope.launch {
            offer.value = try {
                check.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return@launch // Settings unreadable: check again on the next resume.
            } catch (_: RuntimeException) {
                return@launch // Room reports an unreadable database as a runtime exception.
            }
        }
    }

    fun onTranslate() {
        val current = answer() ?: return
        translations.startBatch(current.to)
    }

    fun onNotNow() {
        answer()
    }

    fun onCancel() = translations.cancelBatch()

    fun onResultShown() = translations.dismissBatch()

    /** Closes the offer and stores the language, so it is not offered again on the next resume. */
    private fun answer(): TranslationOffer? {
        val current = offer.value ?: return null
        // A check still running must not bring the answered offer back.
        checking?.cancel()
        offer.value = null
        viewModelScope.launch {
            try {
                check.answered(current)
            } catch (_: IOException) {
                // Not stored: the offer comes back next time, which is the safe side.
            }
        }
        return current
    }
}
