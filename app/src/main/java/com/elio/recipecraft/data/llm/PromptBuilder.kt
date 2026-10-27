package com.elio.recipecraft.data.llm

import android.content.Context
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.di.IoDispatcher
import com.elio.recipecraft.domain.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Reads a bundled text asset by its path under `assets/`. Blocking. */
fun interface AssetReader {
    fun read(path: String): String
}

class AndroidAssetReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssetReader {
    override fun read(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}

/** A ready request and the language it asks the model to write in, which the recipes it yields keep. */
data class Prompt(val request: LlmRequest, val language: Language)

/** Builds requests from the prompt templates in `assets/prompts/{en,zh}/`. */
class PromptBuilder @Inject constructor(
    private val assets: AssetReader,
    private val languageSettings: AppLanguageSettings,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** A chat turn in the current app language; [history] is sent as is and [request] goes through `generate.md`. */
    suspend fun chat(history: List<LlmMessage>, request: String): Prompt {
        val language = languageSettings.resolvedLanguage
        val user = template(language, "generate", mapOf("request" to request))
        return Prompt(LlmRequest(history + LlmMessage(LlmMessage.Role.USER, user), system(language)), language)
    }

    /** Asks the model to fix [badReply], in the [language] of the turn that produced it. */
    suspend fun repair(badReply: String, problem: String, language: Language): Prompt {
        val user = template(language, "repair", mapOf("reply" to badReply, "problem" to problem))
        return Prompt(LlmRequest(listOf(LlmMessage(LlmMessage.Role.USER, user)), system(language)), language)
    }

    private suspend fun system(language: Language) = template(language, "system", emptyMap())

    private suspend fun template(language: Language, name: String, values: Map<String, String>): String {
        val text = withContext(ioDispatcher) { assets.read("prompts/${language.folder}/$name.md") }
        return fill(text, values + ("languageName" to languageName(language)))
    }

    private val Language.folder get() = name.lowercase()

    private fun languageName(language: Language) = when (language) {
        Language.EN -> "English"
        Language.ZH -> "简体中文"
    }

    internal companion object {
        private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")

        /** Fills every `{{key}}` in one pass, so a value that contains braces is never expanded again. */
        fun fill(template: String, values: Map<String, String>): String {
            val missing = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.filterNot(values::containsKey).toSet()
            require(missing.isEmpty()) { "No value for placeholders $missing" }
            return PLACEHOLDER.replace(template) { values.getValue(it.groupValues[1]) }
        }
    }
}
