package com.elio.recipecraft.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.nutrition.RowError
import com.elio.recipecraft.data.nutrition.TableImportError
import com.elio.recipecraft.data.recipe.RecipeImportError

/**
 * Every error the app shows, mapped to its message. Each message says what went wrong and what to
 * do about it, and exists in English and Chinese; `ErrorMessagesTest` checks both.
 */
object ErrorMessages {

    /**
     * The message for [kind]. [inSettings] picks the wording for the provider settings, where the
     * fields to fix are on screen, so the message points at them rather than at Settings.
     */
    @StringRes
    fun llm(kind: LlmErrorKind, inSettings: Boolean = false): Int = when (kind) {
        LlmErrorKind.InvalidKey -> if (inSettings) R.string.error_invalid_key_settings else R.string.error_invalid_key
        LlmErrorKind.RateLimited -> R.string.error_rate_limited
        LlmErrorKind.Network -> R.string.error_network
        LlmErrorKind.Timeout -> R.string.error_timeout
        is LlmErrorKind.Server -> R.string.error_server
        LlmErrorKind.MalformedResponse -> R.string.error_malformed_response
        LlmErrorKind.MissingKey -> if (inSettings) R.string.error_missing_key_settings else R.string.error_missing_key
        LlmErrorKind.MissingModel -> if (inSettings) R.string.error_missing_model_settings else R.string.error_missing_model
        LlmErrorKind.InvalidEndpoint ->
            if (inSettings) R.string.error_invalid_endpoint_settings else R.string.error_invalid_endpoint
        LlmErrorKind.KeyStoreUnavailable ->
            if (inSettings) R.string.error_keystore_unavailable_settings else R.string.error_keystore_unavailable
        LlmErrorKind.SettingsUnavailable -> R.string.error_settings_unavailable
    }

    /** Shown while a rate-limited request waits; takes the seconds left. */
    @StringRes
    val retrying: Int = R.string.error_rate_limited_retrying

    @StringRes
    fun translation(error: TranslationError): Int = when (error) {
        is TranslationError.Llm -> llm(error.kind)
        TranslationError.Rejected -> R.string.translate_error_rejected
        TranslationError.Storage -> R.string.translate_error_storage
    }

    @StringRes
    fun modify(error: ModifyError): Int = when (error) {
        is ModifyError.Llm -> llm(error.kind)
        ModifyError.Rejected -> R.string.modify_error_rejected
    }

    /** Why a nutrition table CSV could not be imported. */
    @StringRes
    fun tableImport(error: TableImportError): Int = when (error) {
        TableImportError.EMPTY -> R.string.import_error_empty
        TableImportError.WRONG_HEADER -> R.string.import_error_wrong_header
        TableImportError.TOO_LARGE -> R.string.import_error_too_large
        TableImportError.NOT_UTF8 -> R.string.import_error_not_utf8
        TableImportError.NO_VALID_ROWS -> R.string.import_error_no_valid_rows
        TableImportError.UNREADABLE -> R.string.import_error_unreadable
        TableImportError.SAVE_FAILED -> R.string.import_error_save_failed
    }

    /** Why one CSV row was skipped; shown after "Row n:". */
    @StringRes
    fun row(error: RowError): Int = when (error) {
        RowError.WRONG_COLUMN_COUNT -> R.string.row_error_wrong_column_count
        RowError.MISSING_NAME -> R.string.row_error_missing_name
        RowError.INVALID_KCAL -> R.string.row_error_invalid_kcal
        RowError.KCAL_TOO_HIGH -> R.string.row_error_kcal_too_high
        RowError.DUPLICATE_NAME -> R.string.row_error_duplicate_name
        RowError.BAD_QUOTES -> R.string.row_error_bad_quotes
    }

    /** Why a recipe export file could not be imported. */
    @StringRes
    fun recipeImport(error: RecipeImportError): Int = when (error) {
        RecipeImportError.TOO_LARGE -> R.string.recipe_import_error_too_large
        RecipeImportError.NOT_AN_EXPORT -> R.string.recipe_import_error_not_an_export
        RecipeImportError.NEWER_VERSION -> R.string.recipe_import_error_newer_version
        RecipeImportError.UNREADABLE -> R.string.import_error_unreadable
        RecipeImportError.SAVE_FAILED -> R.string.recipe_import_error_save_failed
    }

    /** The model returned fewer full-day sets ([sets]) or recipes than asked; takes the asked and got counts. */
    @PluralsRes
    fun shortfall(sets: Boolean): Int = if (sets) R.plurals.generation_short_sets else R.plurals.generation_short_recipes

    /** A full-day set is missing meals; takes the day and the meals. */
    @StringRes
    val incompleteSet: Int = R.string.generation_incomplete_set
}

/** Failures of the app's own storage and files, each with its message. */
enum class AppError(@StringRes val message: Int) {
    CHAT_SAVE_FAILED(R.string.chat_save_failed),
    CHAT_RECIPE_UNREADABLE(R.string.chat_recipe_failed),
    LIBRARY_LOAD_FAILED(R.string.library_load_failed),
    EXPORT_FAILED(R.string.library_export_failed),
    RECIPE_LOAD_FAILED(R.string.recipe_load_failed),
    RECIPE_NOT_FOUND(R.string.recipe_not_found),
    RECIPE_DELETE_FAILED(R.string.recipe_delete_failed),
    RECIPE_RESTORE_FAILED(R.string.recipe_restore_failed),
    MODIFY_SAVE_FAILED(R.string.recipe_modify_save_failed),
    SETTINGS_LOAD_FAILED(R.string.settings_load_failed),
    SETTINGS_SAVE_FAILED(R.string.settings_save_failed),
    MODELS_EMPTY(R.string.settings_models_empty),
    TABLES_LOAD_FAILED(R.string.nutrition_tables_load_failed),
    TABLES_ACTION_FAILED(R.string.nutrition_tables_action_failed),
}

/** The localized message for [kind]; [inSettings] as in [ErrorMessages.llm]. */
@Composable
fun llmErrorText(kind: LlmErrorKind, inSettings: Boolean = false): String {
    val message = ErrorMessages.llm(kind, inSettings)
    return if (kind is LlmErrorKind.Server) stringResource(message, kind.statusCode) else stringResource(message)
}

/** The localized message for why a recipe could not be translated. */
@Composable
fun translationErrorText(error: TranslationError): String =
    if (error is TranslationError.Llm) llmErrorText(error.kind) else stringResource(ErrorMessages.translation(error))

/** The localized message for why a recipe could not be changed. */
@Composable
fun modifyErrorText(error: ModifyError): String =
    if (error is ModifyError.Llm) llmErrorText(error.kind) else stringResource(ErrorMessages.modify(error))

/** "Rate limited — retrying in Ns…" */
@Composable
fun retryingText(seconds: Int): String = stringResource(ErrorMessages.retrying, seconds)

/** Errors the user fixes in Settings, so the error offers a way there. */
val LlmErrorKind.isFixedInSettings: Boolean
    get() = when (this) {
        LlmErrorKind.InvalidKey,
        LlmErrorKind.MissingKey,
        LlmErrorKind.MissingModel,
        LlmErrorKind.InvalidEndpoint,
        LlmErrorKind.KeyStoreUnavailable,
        -> true
        else -> false
    }
