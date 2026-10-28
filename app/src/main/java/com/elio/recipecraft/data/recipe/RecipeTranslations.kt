package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.RecipeTranslator
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.llm.TranslationResult
import com.elio.recipecraft.di.ApplicationScope
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the batch translation of saved recipes is. */
sealed interface TranslationBatchState {
    data object Idle : TranslationBatchState

    /**
     * Translating recipe [current] (1-based) of [total]; [current] is 0 while the batch starts.
     * [retryingInSeconds] is set while a rate-limited request waits to be sent again.
     */
    data class Running(val current: Int, val total: Int, val retryingInSeconds: Int? = null) : TranslationBatchState

    /**
     * [translated] recipes were translated and saved; [failed] were left as they were, with [error]
     * the reason for the last failure. [cancelled] when the user stopped the batch, in which case
     * the recipes not reached are counted in neither.
     */
    data class Finished(
        val translated: Int,
        val failed: Int,
        val cancelled: Boolean,
        val error: TranslationError?,
    ) : TranslationBatchState
}

/**
 * Translates saved recipes in place, one at a time or as a batch, and saves each with its calories
 * worked out again (zh recipes match imported tables on `name`). The batch runs on the app scope,
 * so it survives rotation and leaving the screen; each recipe is saved as soon as it is translated,
 * so cancelling keeps those and leaves the rest untouched.
 */
@Singleton
class RecipeTranslations @Inject constructor(
    private val clientFactory: LlmClientFactory,
    private val translator: RecipeTranslator,
    private val repository: RecipeRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _batch = MutableStateFlow<TranslationBatchState>(TranslationBatchState.Idle)
    val batch: StateFlow<TranslationBatchState> = _batch.asStateFlow()

    private var job: Job? = null

    /** Translates every saved recipe not in [target]; does nothing while a batch runs. */
    fun startBatch(target: Language) {
        if (_batch.value is TranslationBatchState.Running) return
        _batch.value = TranslationBatchState.Running(0, 0)
        job = scope.launch { runBatch(target) }
    }

    /** Stops the batch after the recipes already saved. */
    fun cancelBatch() {
        job?.cancel()
    }

    /** Forgets a finished batch, once the user has seen how it went. */
    fun dismissBatch() {
        if (_batch.value is TranslationBatchState.Finished) _batch.value = TranslationBatchState.Idle
    }

    /**
     * Translates [recipe] into [target] and saves it; returns why it failed, or null. [onRetryWait]
     * gets the seconds a rate-limit wait lasts, then null once the model answers.
     */
    suspend fun translate(recipe: Recipe, target: Language, onRetryWait: (Int?) -> Unit = {}): TranslationError? {
        if (recipe.language == target) return null
        val client = when (val result = clientFactory.forActiveProvider()) {
            is LlmClientResult.Ready -> result.client
            is LlmClientResult.Unavailable -> return TranslationError.Llm(result.kind)
        }
        return translateAndSave(recipe, target, client, onRetryWait)
    }

    private suspend fun runBatch(target: Language) {
        var translated = 0
        var failed = 0
        var error: TranslationError? = null
        try {
            val recipes = try {
                repository.notIn(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Room reports an unreadable database as a runtime exception.
                _batch.value = TranslationBatchState.Finished(0, 0, cancelled = false, TranslationError.Storage)
                return
            }
            val client = when (val result = clientFactory.forActiveProvider()) {
                is LlmClientResult.Ready -> result.client
                is LlmClientResult.Unavailable -> {
                    _batch.value = TranslationBatchState.Finished(0, recipes.size, cancelled = false, TranslationError.Llm(result.kind))
                    return
                }
            }
            for ((index, recipe) in recipes.withIndex()) {
                val running = TranslationBatchState.Running(index + 1, recipes.size)
                _batch.value = running
                val failure = translateAndSave(recipe, target, client) { seconds ->
                    _batch.value = running.copy(retryingInSeconds = seconds)
                }
                if (failure == null) {
                    translated++
                    continue
                }
                failed++
                error = failure
                if (failure.stopsBatch) {
                    // The next request would fail the same way, so leave the rest as they are.
                    failed += recipes.size - index - 1
                    break
                }
            }
            _batch.value = TranslationBatchState.Finished(translated, failed, cancelled = false, error)
        } catch (e: CancellationException) {
            _batch.value = TranslationBatchState.Finished(translated, failed, cancelled = true, error)
            throw e
        }
    }

    private suspend fun translateAndSave(
        recipe: Recipe,
        target: Language,
        client: LlmClient,
        onRetryWait: (Int?) -> Unit,
    ): TranslationError? =
        when (val result = translator.translate(recipe, target, client, onRetryWait)) {
            is TranslationResult.Failure -> result.error
            is TranslationResult.Success -> try {
                // A recipe deleted while it was being translated stays deleted.
                repository.update(result.recipe)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                TranslationError.Storage
            }
        }

    /** A failure that is about the connection or settings rather than this recipe. */
    private val TranslationError.stopsBatch: Boolean
        get() = this is TranslationError.Llm && kind != LlmErrorKind.MalformedResponse
}
