package com.elio.recipecraft.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import com.elio.recipecraft.ui.assertTouchHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The provider settings: headings, radio semantics, touch targets, and the failed-load and error states. */
@RunWith(AndroidJUnit4::class)
class ProviderSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var state by mutableStateOf(SettingsUiState(loading = false))
    private var retries = 0
    private var selected: ProviderType? = null

    private fun setContent() {
        compose.setContent {
            RecipeCraftTheme {
                ProviderSettings(
                    state = state,
                    onProviderSelected = { selected = it },
                    onModelChanged = {},
                    onBaseUrlChanged = {},
                    onProtocolSelected = {},
                    onApiKeyChanged = {},
                    onToggleKeyVisibility = {},
                    onClearApiKey = {},
                    onTestConnection = {},
                    onFetchModels = {},
                    onModelPicked = {},
                    onDismissModelPicker = {},
                    onRetryLoad = { retries++ },
                )
            }
        }
    }

    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun sectionsAreHeadingsAndProvidersAreLabelledRadiosOfFullSize() {
        compose.enableAccessibilityChecks()
        setContent()
        compose.onNode(hasText(context.getString(R.string.settings_provider)) and isHeading()).assertExists()
        compose.onNode(hasText(context.getString(R.string.provider_claude)) and isRadio)
            .assertIsSelected()
            .assertTouchHeightIsAtLeast(48.dp)
        compose.onNode(hasText(context.getString(R.string.provider_openai)) and isRadio)
            .assertIsNotSelected()
            .assertTouchHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(ProviderType.OPENAI, selected)
        compose.onNodeWithText(context.getString(R.string.settings_test_connection)).assertTouchHeightIsAtLeast(48.dp)
    }

    @Test
    fun whileLoadingTheProvidersAreDisabled() {
        state = SettingsUiState(loading = true)
        setContent()
        compose.onNode(hasText(context.getString(R.string.provider_openai)) and isRadio).assertIsNotEnabled()
        compose.onNodeWithTag(ProviderSettingsTestTags.API_KEY).assertIsNotEnabled()
    }

    @Test
    fun afterAFailedLoadTheFieldsAreReadOnlyUntilRetry() {
        compose.enableAccessibilityChecks()
        state = SettingsUiState(loading = false, loadFailed = true)
        setContent()
        compose.onNodeWithTag(ProviderSettingsTestTags.API_KEY).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_load_failed)).assertIsDisplayed()
        // Another provider can still be picked; it loads its own settings.
        compose.onNode(hasText(context.getString(R.string.provider_openai)) and isRadio).assertIsEnabled()
        compose.onNodeWithTag(ProviderSettingsTestTags.RETRY).assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun aFailedKeySaveIsShownUnderTheKey() {
        state = SettingsUiState(loading = false, failedSaves = setOf(SettingsField.API_KEY))
        setContent()
        compose.onNodeWithText(context.getString(R.string.settings_save_failed)).assertIsDisplayed()
    }

    @Test
    fun aMissingKeyReadsNaturallyInSettings() {
        state = SettingsUiState(loading = false, connectionTest = ConnectionTestState.Failed(LlmErrorKind.MissingKey))
        setContent()
        val expected = context.getString(R.string.settings_connection_failed, context.getString(R.string.error_missing_key_settings))
        compose.onNodeWithText(expected).assertIsDisplayed()
    }
}
