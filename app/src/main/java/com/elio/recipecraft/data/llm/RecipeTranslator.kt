package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/** Why a recipe could not be translated; the recipe is left as it was. */
sealed interface TranslationError {
    /** The request failed, or could not be made with the current settings. */
    data class Llm(val kind: LlmErrorKind) : TranslationError

    /** The reply was not a recipe, or it changed more than the text. */
    data object Rejected : TranslationError

    /** The saved recipes could not be read or written. */
    data object Storage : TranslationError
}

sealed interface TranslationResult {
    data class Success(val recipe: Recipe) : TranslationResult
    data class Failure(val error: TranslationError) : TranslationResult
}

/**
 * Translates one recipe's text with the model, through `translate.md`. The reply is collected in
 * full, its JSON read with [RecipeParser], and the result merged and checked by
 * [RecipeTranslation], so only the text and `language` can change. There is no repair turn: a
 * reply that cannot be used fails, and the recipe stays as it was.
 */
class RecipeTranslator @Inject constructor(
    private val promptBuilder: PromptBuilder,
    private val parser: RecipeParser,
    private val clock: Clock,
) {

    suspend fun translate(recipe: Recipe, target: Language, client: LlmClient): TranslationResult {
        val prompt = promptBuilder.translate(recipe.toWireJson().toString(), target)
        val reply = StringBuilder()
        var error: LlmErrorKind? = null
        client.stream(prompt.request)
            .takeWhile { event ->
                when (event) {
                    is LlmEvent.Delta -> {
                        reply.append(event.text)
                        true
                    }
                    is LlmEvent.Done -> false
                    is LlmEvent.Error -> {
                        error = event.kind
                        false
                    }
                }
            }
            .collect()
        error?.let { return TranslationResult.Failure(TranslationError.Llm(it)) }
        val wire = (parser.parse(reply.toString()) as? RecipeParseResult.Success)?.batch?.recipes?.singleOrNull()
            ?: return TranslationResult.Failure(TranslationError.Rejected)
        val translated = RecipeTranslation.merge(recipe, wire, target, clock.millis())
            ?: return TranslationResult.Failure(TranslationError.Rejected)
        return TranslationResult.Success(translated)
    }
}
