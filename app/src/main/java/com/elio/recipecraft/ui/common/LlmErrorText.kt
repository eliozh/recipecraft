package com.elio.recipecraft.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmErrorKind

/** The localized message for [kind]. */
@Composable
fun llmErrorText(kind: LlmErrorKind): String = when (kind) {
    LlmErrorKind.InvalidKey -> stringResource(R.string.error_invalid_key)
    LlmErrorKind.RateLimited -> stringResource(R.string.error_rate_limited)
    LlmErrorKind.Network -> stringResource(R.string.error_network)
    LlmErrorKind.Timeout -> stringResource(R.string.error_timeout)
    is LlmErrorKind.Server -> stringResource(R.string.error_server, kind.statusCode)
    LlmErrorKind.MalformedResponse -> stringResource(R.string.error_malformed_response)
    LlmErrorKind.MissingKey -> stringResource(R.string.error_missing_key)
    LlmErrorKind.MissingModel -> stringResource(R.string.error_missing_model)
    LlmErrorKind.InvalidEndpoint -> stringResource(R.string.error_invalid_endpoint)
    LlmErrorKind.KeyStoreUnavailable -> stringResource(R.string.error_keystore_unavailable)
    LlmErrorKind.SettingsUnavailable -> stringResource(R.string.error_settings_unavailable)
}

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
