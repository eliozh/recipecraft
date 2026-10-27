package com.elio.recipecraft.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.R
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real ChatScreen and ChatViewModel against a scripted LLM client. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = ScriptedClient()
    private var openedSettings = false

    private fun setContent(result: LlmClientResult = LlmClientResult.Ready(client)) {
        val viewModel = ChatViewModel(FixedFactory(result))
        compose.setContent {
            RecipeCraftTheme {
                ChatScreen(onOpenSettings = { openedSettings = true }, viewModel = viewModel)
            }
        }
    }

    private fun send(text: String) {
        compose.onNodeWithTag(ChatTestTags.INPUT).performTextInput(text)
        compose.onNodeWithText(context.getString(R.string.chat_send)).performClick()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun streamsTheReplyAndStopCancelsIt() {
        setContent()
        send("Breakfast idea?")
        compose.onNodeWithText("Breakfast idea?").assertIsDisplayed()

        client.events.trySend(LlmEvent.Delta("Try"))
        waitForText("Try")
        client.events.trySend(LlmEvent.Delta(" a tomato"))
        waitForText("Try a tomato")

        compose.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        compose.waitUntil(5_000) { client.cancelled }
        compose.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()

        client.events.trySend(LlmEvent.Delta(" omelette"))
        compose.waitForIdle()
        compose.onNodeWithText("Try a tomato").assertIsDisplayed()
    }

    @Test
    fun aRejectedKeyShowsTheLocalizedMessageAndALinkToSettings() {
        setContent()
        send("Hi")
        client.events.trySend(LlmEvent.Error(LlmErrorKind.InvalidKey))

        waitForText(context.getString(R.string.error_invalid_key))
        compose.onNodeWithText(context.getString(R.string.chat_open_settings)).performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun noModelAsksToPickOneInSettings() {
        setContent(LlmClientResult.Unavailable(LlmErrorKind.MissingModel))
        send("Hi")

        waitForText(context.getString(R.string.error_missing_model))
        compose.onNodeWithText("Hi").assertIsDisplayed() // still in the input, ready to resend
        compose.onNodeWithText(context.getString(R.string.chat_open_settings)).assertIsDisplayed()
    }

    private class ScriptedClient : LlmClient {
        val events = Channel<LlmEvent>(Channel.UNLIMITED)

        @Volatile
        var cancelled = false

        override fun stream(request: LlmRequest): Flow<LlmEvent> =
            events.receiveAsFlow().onCompletion { if (it is CancellationException) cancelled = true }

        override suspend fun listModels() = ModelListResult.Success(emptyList())
    }

    private class FixedFactory(private val result: LlmClientResult) : LlmClientFactory {
        override suspend fun forActiveProvider(requireModel: Boolean) = result
        override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean) = result
    }
}
