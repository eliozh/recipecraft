package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val prompt = promptBuilder.translate(toJson(recipe).toString(), target)
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

    internal companion object {
        /** The recipe in the schema the prompts describe, without the fields the app owns (ids, timestamps). */
        fun toJson(recipe: Recipe): JsonObject = buildJsonObject {
            put("name", recipe.name)
            put("mealType", recipe.mealType?.name?.lowercase())
            put("servings", recipe.servings)
            put("aiKcal", recipe.calories.aiTotal)
            put(
                "ingredients",
                buildJsonArray {
                    recipe.ingredients.forEach { ingredient ->
                        add(
                            buildJsonObject {
                                put("name", ingredient.name)
                                put("nameEn", ingredient.nameEn)
                                put("quantity", ingredient.quantity)
                                put("unit", ingredient.unit)
                                put("grams", ingredient.grams)
                                put("aiKcal", ingredient.aiKcal)
                            },
                        )
                    }
                },
            )
            put("tools", JsonArray(recipe.tools.map(::JsonPrimitive)))
            put(
                "steps",
                buildJsonArray {
                    recipe.steps.forEach { step ->
                        add(
                            buildJsonObject {
                                put("text", step.text)
                                put("minutes", step.minutes)
                            },
                        )
                    }
                },
            )
        }
    }
}
