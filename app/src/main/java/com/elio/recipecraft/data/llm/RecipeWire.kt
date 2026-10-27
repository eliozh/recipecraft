package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The `RecipeBatch` JSON the model returns, as the prompts in assets/prompts/{en,zh}/system.md
 * describe it. Changing it needs the prompts updated in both languages (SPEC: "Ask first").
 *
 * {
 *   "recipes": [{
 *     "name": "Spinach omelette",
 *     "mealType": "breakfast" | "lunch" | "dinner" | "snack" | "dessert",
 *     "servings": 2,
 *     "aiKcal": 420,                                  // whole recipe, all servings
 *     "ingredients": [{
 *       "name": "Eggs",                               // display name, app language
 *       "nameEn": "egg",                              // hidden match key, English
 *       "quantity": "4", "unit": "",
 *       "grams": 200,                                 // null when a weight makes no sense
 *       "aiKcal": 286
 *     }],
 *     "tools": ["Non-stick pan"],
 *     "steps": [{ "text": "Whisk the eggs.", "minutes": 2 }],
 *     "set": 1                                        // optional, see below
 *   }]
 * }
 *
 * `set` (added with the generation composer, T9) is optional: a 1-based number that marks which
 * full-day set a recipe belongs to. Replies without it, including every earlier one, still decode.
 * RecipeAssembler turns each set number into one generated setId and, for a full-day request whose
 * reply leaves `set` out, falls back to grouping by meal order (see SetGrouping).
 *
 * The app adds id, timestamps, language, setId and the calorie source. Every field has a default
 * so a missing optional one never fails the whole reply; RecipeParser checks the required ones.
 */

@Serializable
data class RecipeBatchWire(val recipes: List<RecipeWire> = emptyList())

@Serializable
data class RecipeWire(
    val name: String = "",
    val mealType: String? = null,
    val servings: Double? = null,
    /** The model's estimate for the whole recipe. */
    val aiKcal: Double? = null,
    val ingredients: List<IngredientWire> = emptyList(),
    val tools: List<String> = emptyList(),
    val steps: List<@Serializable(with = TolerantStepSerializer::class) StepWire> = emptyList(),
    /** The 1-based full-day set this recipe belongs to; null for a single recipe. */
    val set: Double? = null,
)

@Serializable
data class IngredientWire(
    val name: String = "",
    val nameEn: String = "",
    val quantity: String = "",
    val unit: String = "",
    val grams: Double? = null,
    val aiKcal: Double? = null,
)

@Serializable
data class StepWire(
    val text: String = "",
    val minutes: Double? = null,
)

/** Models sometimes write steps as plain strings; read those as steps without a time. */
internal object TolerantStepSerializer : JsonTransformingSerializer<StepWire>(StepWire.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonObject(mapOf("text" to element)) else element
}

/**
 * The recipe in the schema the prompts describe, for prompts that send a recipe to the model
 * (`translate.md`, `modify.md`). Leaves out the fields the app owns: ids, set, timestamps.
 */
internal fun Recipe.toWireJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("mealType", mealType?.name?.lowercase())
    put("servings", servings)
    put("aiKcal", calories.aiTotal)
    put(
        "ingredients",
        buildJsonArray {
            ingredients.forEach { ingredient ->
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
    put("tools", JsonArray(tools.map(::JsonPrimitive)))
    put(
        "steps",
        buildJsonArray {
            steps.forEach { step ->
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
