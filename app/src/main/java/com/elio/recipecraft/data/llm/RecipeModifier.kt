package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.domain.Recipe
import java.time.Clock
import javax.inject.Inject

/** Why a recipe could not be changed; the recipe is left as it was. */
sealed interface ModifyError {
    /** The request failed, or could not be made with the current settings. */
    data class Llm(val kind: LlmErrorKind) : ModifyError

    /** Neither the reply nor the repair turn held a usable recipe. */
    data object Rejected : ModifyError
}

sealed interface ModifyResult {
    data class Success(val recipe: Recipe) : ModifyResult
    data class Failure(val error: ModifyError) : ModifyResult
}

/**
 * Changes one recipe with the model, as the user's instruction says, through `modify.md`. Used by
 * every "Modify with AI" action, whatever holds the recipe (a chat card, a saved recipe).
 *
 * The reply is read with [RecipeParser]; when it cannot be read, one repair turn runs, as for
 * generation. The first recipe in the reply is the result: a model that returns more than one
 * has misread the prompt, and the extra recipes are ignored rather than failing the change. The
 * result is assembled like a generated recipe, so its calories are worked out against the current
 * tables, and then keeps the original's id, set, language, creation time and, when the original
 * has one, its meal type, so a recipe in a full-day set stays in its place.
 */
class RecipeModifier @Inject constructor(
    private val clientFactory: LlmClientFactory,
    private val promptBuilder: PromptBuilder,
    private val parser: RecipeParser,
    private val assembler: RecipeAssembler,
    private val nutrition: NutritionLookups,
    private val clock: Clock,
) {

    /** [onRetryWait] gets the seconds a rate-limit wait lasts, then null once the model answers. */
    suspend fun modify(recipe: Recipe, instruction: String, onRetryWait: (Int?) -> Unit = {}): ModifyResult {
        val client = when (val result = clientFactory.forActiveProvider()) {
            is LlmClientResult.Ready -> result.client
            is LlmClientResult.Unavailable -> return ModifyResult.Failure(ModifyError.Llm(result.kind))
        }
        val prompt = promptBuilder.modify(recipe, instruction)
        val reply = client.collect(prompt.request, onRetryWait)
        reply.error?.let { return ModifyResult.Failure(ModifyError.Llm(it)) }
        val wire = when (val parsed = parser.parse(reply.text)) {
            is RecipeParseResult.Success -> parsed.batch.recipes.first()
            is RecipeParseResult.Failure -> repair(client, reply.text, parsed, prompt, onRetryWait)
                ?: return ModifyResult.Failure(ModifyError.Rejected)
        }
        return ModifyResult.Success(merge(recipe, wire))
    }

    /** The first recipe of the one repair turn, or null when that fails too. */
    private suspend fun repair(
        client: LlmClient,
        reply: String,
        failure: RecipeParseResult.Failure,
        prompt: Prompt,
        onRetryWait: (Int?) -> Unit,
    ): RecipeWire? {
        val repair = promptBuilder.repair(reply, failure.description, prompt.language)
        val repaired = client.collect(repair.request, onRetryWait)
        if (repaired.error != null) return null
        return (parser.parse(repaired.text) as? RecipeParseResult.Success)?.batch?.recipes?.first()
    }

    private suspend fun merge(original: Recipe, wire: RecipeWire): Recipe {
        val changed = assembler.assemble(RecipeBatchWire(listOf(wire)), original.language, nutrition.current())
            .recipes.single()
        return changed.copy(
            id = original.id,
            setId = original.setId,
            mealType = original.mealType ?: changed.mealType,
            language = original.language,
            createdAt = original.createdAt,
            updatedAt = maxOf(clock.millis(), original.updatedAt),
        )
    }

    private class Reply(val text: String, val error: LlmErrorKind?)

    private suspend fun LlmClient.collect(request: LlmRequest, onRetryWait: (Int?) -> Unit): Reply {
        val text = StringBuilder()
        val error = collectReply(request, onRetryWait) { text.append(it) }
        return Reply(text.toString(), error)
    }
}
