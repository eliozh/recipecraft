package com.elio.recipecraft.data.llm

import javax.inject.Inject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The outcome of reading recipes out of a model reply. */
sealed interface RecipeParseResult {
    /** [prose] is the reply with the JSON block taken out. */
    data class Success(val batch: RecipeBatchWire, val prose: String) : RecipeParseResult

    data class Failure(val reason: Reason, val detail: String? = null) : RecipeParseResult {
        /** A short English description of the problem, for the repair prompt. */
        val description: String get() = listOfNotNull(reason.description, detail).joinToString(": ")
    }

    enum class Reason(val description: String) {
        /** A plain prose reply; not an error. */
        NO_JSON("the reply has no JSON block"),
        INVALID_JSON("the JSON is not valid"),
        NO_RECIPES("the \"recipes\" array is missing or empty"),
        MISSING_NAME("a recipe has no \"name\""),
        NO_INGREDIENTS("a recipe has no \"ingredients\""),
        NO_STEPS("a recipe has no \"steps\""),
    }
}

/** What a reply shows while it is still streaming. */
data class StreamingReply(val prose: String, val hasJson: Boolean)

/** Pulls the `RecipeBatch` JSON out of a streamed model reply and decodes it leniently. */
class RecipeParser @Inject constructor() {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
        explicitNulls = false
    }

    fun parse(reply: String): RecipeParseResult {
        val block = extract(reply)
            ?: return RecipeParseResult.Failure(RecipeParseResult.Reason.NO_JSON)
        val batch = try {
            json.decodeFromJsonElement(RecipeBatchWire.serializer(), normalize(json.parseToJsonElement(block.json)))
        } catch (e: SerializationException) {
            return RecipeParseResult.Failure(RecipeParseResult.Reason.INVALID_JSON, e.message?.take(MAX_DETAIL))
        } catch (e: IllegalArgumentException) {
            return RecipeParseResult.Failure(RecipeParseResult.Reason.INVALID_JSON, e.message?.take(MAX_DETAIL))
        }
        return validate(batch)?.let { RecipeParseResult.Success(it, block.prose) }
            ?: RecipeParseResult.Failure(problemOf(batch))
    }

    /** The prose before any JSON, so a reply in progress never shows raw JSON. */
    fun streaming(reply: String): StreamingReply {
        val fence = reply.indexOf(FENCE)
        return when {
            fence >= 0 -> StreamingReply(reply.substring(0, fence).trim(), hasJson = true)
            reply.trimStart().startsWith("{") -> StreamingReply("", hasJson = true)
            else -> StreamingReply(reply, hasJson = false)
        }
    }

    private class Block(val json: String, val prose: String)

    private fun extract(reply: String): Block? {
        FENCED.findAll(reply).firstOrNull { it.groupValues[1].trimStart().firstOrNull() in JSON_STARTS }?.let {
            return Block(it.groupValues[1], prose(reply, it.range.first, it.range.last + 1))
        }
        // A bare object, as a repair reply returns; it has to look like recipes, so prose with braces stays prose.
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}') + 1
        if (start < 0 || end <= start) return null
        val candidate = reply.substring(start, end)
        if (RECIPE_KEYS.none { it in candidate }) return null
        return Block(candidate, prose(reply, start, end))
    }

    /** The reply without the characters from [start] until [end]. */
    private fun prose(reply: String, start: Int, end: Int) =
        listOf(reply.substring(0, start), reply.substring(end)).map(String::trim).filter(String::isNotEmpty)
            .joinToString("\n")

    /** Accepts a batch, a single recipe object or a bare array of recipes. */
    private fun normalize(element: JsonElement): JsonElement = when {
        element is JsonArray -> JsonObject(mapOf("recipes" to element))
        element is JsonObject && "recipes" !in element && "ingredients" in element ->
            JsonObject(mapOf("recipes" to JsonArray(listOf(element))))
        else -> element
    }

    /** Drops blank entries and returns null when a required field is missing. */
    private fun validate(batch: RecipeBatchWire): RecipeBatchWire? {
        val recipes = batch.recipes.map { recipe ->
            recipe.copy(
                ingredients = recipe.ingredients.filter { it.name.isNotBlank() },
                tools = recipe.tools.filter { it.isNotBlank() },
                steps = recipe.steps.filter { it.text.isNotBlank() },
            )
        }
        val valid = recipes.isNotEmpty() && recipes.all {
            it.name.isNotBlank() && it.ingredients.isNotEmpty() && it.steps.isNotEmpty()
        }
        return if (valid) RecipeBatchWire(recipes) else null
    }

    private fun problemOf(batch: RecipeBatchWire): RecipeParseResult.Reason = when {
        batch.recipes.isEmpty() -> RecipeParseResult.Reason.NO_RECIPES
        batch.recipes.any { it.name.isBlank() } -> RecipeParseResult.Reason.MISSING_NAME
        batch.recipes.any { r -> r.ingredients.none { it.name.isNotBlank() } } -> RecipeParseResult.Reason.NO_INGREDIENTS
        else -> RecipeParseResult.Reason.NO_STEPS
    }

    private companion object {
        const val FENCE = "```"
        const val MAX_DETAIL = 200

        /** A fenced block with any or no language tag; an unclosed fence runs to the end of the reply. */
        val FENCED = Regex("```[ \\t]*[A-Za-z]*[ \\t]*\\r?\\n?([\\s\\S]*?)(?:```|\\z)")
        val RECIPE_KEYS = listOf("\"recipes\"", "\"ingredients\"")
        val JSON_STARTS = setOf('{', '[')
    }
}
