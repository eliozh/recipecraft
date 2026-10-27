package com.elio.recipecraft.data.chat

import com.elio.recipecraft.data.recipe.StoredRecipe
import com.elio.recipecraft.data.recipe.toDomain
import com.elio.recipecraft.data.recipe.toStored
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.domain.GenerationShortfall
import com.elio.recipecraft.domain.IncompleteSet
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON columns of the chat tables. A change to the `Stored*` classes here needs a migration. */
internal object ChatJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun recipesToJson(recipes: List<Recipe>): String = json.encodeToString(recipes.map { it.toStored() })

    fun recipesFromJson(value: String): List<Recipe> =
        json.decodeFromString<List<StoredRecipe>>(value).map { it.toDomain() }

    fun requestToJson(request: GenerationRequest?): String? =
        request?.let { json.encodeToString(StoredRequest(it.scope, it.count, it.preferences)) }

    fun requestFromJson(value: String?): GenerationRequest? =
        value?.let { json.decodeFromString<StoredRequest>(it) }?.let { GenerationRequest(it.scope, it.count, it.preferences) }

    fun shortfallToJson(shortfall: GenerationShortfall?): String? = shortfall?.let {
        json.encodeToString(
            StoredShortfall(it.asked, it.got, it.sets, it.incomplete.map { set -> StoredIncompleteSet(set.day, set.missing) }),
        )
    }

    fun shortfallFromJson(value: String?): GenerationShortfall? = value?.let { json.decodeFromString<StoredShortfall>(it) }?.let {
        GenerationShortfall(it.asked, it.got, it.sets, it.incomplete.map { set -> IncompleteSet(set.day, set.missing) })
    }
}

@Serializable
private data class StoredRequest(val scope: GenerationScope, val count: Int, val preferences: String = "")

@Serializable
private data class StoredShortfall(
    val asked: Int,
    val got: Int,
    val sets: Boolean,
    val incomplete: List<StoredIncompleteSet> = emptyList(),
)

@Serializable
private data class StoredIncompleteSet(val day: Int, val missing: List<MealType>)
