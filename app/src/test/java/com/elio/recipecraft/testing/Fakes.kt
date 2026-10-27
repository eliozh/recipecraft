package com.elio.recipecraft.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.NutritionFoodEntity
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.nutrition.NutritionTableDao
import com.elio.recipecraft.data.nutrition.NutritionTableEntity
import com.elio.recipecraft.data.nutrition.NutritionTableSummary
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.data.recipe.RecipeDao
import com.elio.recipecraft.data.recipe.RecipeEntity
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

const val PLACEHOLDER_KEY = "sk-test-PLACEHOLDER-1234"

class FakeSecretStore : SecretStore {
    val keys = mutableMapOf<ProviderType, String>()
    var failReads = false
    var failWrites = false

    override suspend fun getKey(type: ProviderType): String? {
        if (failReads) throw GeneralSecurityException("test")
        return keys[type]
    }

    override suspend fun setKey(type: ProviderType, key: String) {
        if (failWrites) throw GeneralSecurityException("test")
        if (key.isEmpty()) keys.remove(type) else keys[type] = key
    }

    override suspend fun clear(type: ProviderType) {
        keys.remove(type)
    }
}

/** Every read and write runs on the caller, so advanceUntilIdle sees it. */
class InMemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}

class FakeLanguageSettings : AppLanguageSettings {
    var current = AppLanguage.FOLLOW_SYSTEM
    override val choice get() = current
    var resolved = Language.EN
    override val resolvedLanguage get() = resolved
    override fun setChoice(choice: AppLanguage) {
        current = choice
    }
}

/** A client whose reply the test feeds event by event through [events]. */
class FakeLlmClient : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    val events = Channel<LlmEvent>(Channel.UNLIMITED)
    var cancelled = false
    var models: ModelListResult = ModelListResult.Success(emptyList())

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        requests += request
        return events.receiveAsFlow().onCompletion { if (it is CancellationException) cancelled = true }
    }

    override suspend fun listModels(): ModelListResult = models
}

class FakeLlmClientFactory(var result: LlmClientResult) : LlmClientFactory {
    val configs = mutableListOf<Pair<ProviderConfig, Boolean>>()

    override suspend fun forActiveProvider(requireModel: Boolean): LlmClientResult = result

    override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean): LlmClientResult {
        configs += config to requireModel
        return result
    }
}

class FakeNutritionLookups(var lookups: List<NutritionLookup> = emptyList()) : NutritionLookups {
    /** Set it to make [observe] emit each value the test pushes, as a table change would. */
    var changes: Flow<List<NutritionLookup>>? = null

    override suspend fun current(): List<NutritionLookup> = lookups

    override fun observe(): Flow<List<NutritionLookup>> = changes ?: flow { emit(lookups) }
}

/**
 * An in-memory [RecipeDao]. List order stands in for Room's rowid, and search follows the real
 * query: a substring of the lowercased search text, with LIKE's escapes undone.
 */
class FakeRecipeDao : RecipeDao() {
    private val state = MutableStateFlow<List<RecipeEntity>>(emptyList())

    val recipes: List<RecipeEntity> get() = state.value
    val calorieWrites = mutableListOf<String>()
    var failReads = false
    var failWrites = false

    override fun observe(mealType: MealType?, query: String): Flow<List<RecipeEntity>> = readable().map { all ->
        val needle = query.replace(Regex("\\\\(.)"), "$1")
        all.filter { (mealType == null || it.mealType == mealType) && it.searchText.contains(needle) }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<RecipeEntity>> { it.value.createdAt }.thenBy { it.index })
            .map { it.value }
    }

    override fun observeIds(): Flow<List<String>> = readable().map { all -> all.map { it.id } }

    override fun observeCount(): Flow<Int> = readable().map { it.size }

    override suspend fun all(): List<RecipeEntity> {
        if (failReads) throw IllegalStateException("database unreadable")
        return state.value
    }

    override suspend fun upsert(recipes: List<RecipeEntity>) = write { all ->
        recipes.fold(all) { acc, recipe ->
            if (acc.any { it.id == recipe.id }) acc.map { if (it.id == recipe.id) recipe else it } else acc + recipe
        }
    }

    override suspend fun setCalories(id: String, calories: Calories) {
        calorieWrites += id
        write { all -> all.map { if (it.id == id) it.copy(calories = calories) else it } }
    }

    private fun readable() = state.onStart { if (failReads) throw IllegalStateException("database unreadable") }

    private fun write(change: (List<RecipeEntity>) -> List<RecipeEntity>) {
        if (failWrites) throw IllegalStateException("disk full")
        state.value = change(state.value)
    }
}

/**
 * An in-memory [NutritionTableDao]. It inherits the real transaction methods, and like Room it
 * publishes their writes together, so observers never see a table without its foods.
 */
class FakeNutritionTableDao : NutritionTableDao() {
    private data class Snapshot(val tables: List<NutritionTableEntity>, val foods: List<NutritionFoodEntity>)

    private val state = MutableStateFlow(Snapshot(emptyList(), emptyList()))
    private var draft = state.value
    private var transactionDepth = 0
    private var nextId = 1L

    var foodReads = 0
    var failReads = false
    var failWrites = false

    val tables: List<NutritionTableEntity> get() = state.value.tables
    val foods: List<NutritionFoodEntity> get() = state.value.foods

    override fun observeTables(): Flow<List<NutritionTableSummary>> = state
        .onStart { if (failReads) throw IllegalStateException("database unreadable") }
        .map { snapshot ->
            snapshot.tables.sortedWith(compareBy({ it.priority }, { it.id })).map { table ->
                NutritionTableSummary(table.id, table.name, table.priority, table.importedAt, snapshot.foods.count { it.tableId == table.id })
            }
        }

    override suspend fun foods(tableId: Long): List<NutritionFoodEntity> {
        foodReads++
        return state.value.foods.filter { it.tableId == tableId }
    }

    override suspend fun tableIdsInOrder(): List<Long> = draft.tables.sortedWith(compareBy({ it.priority }, { it.id })).map { it.id }

    override suspend fun maxPriority(): Int? = draft.tables.maxOfOrNull { it.priority }

    override suspend fun insertTable(table: NutritionTableEntity): Long {
        val id = nextId++
        write { it.copy(tables = it.tables + table.copy(id = id)) }
        return id
    }

    override suspend fun insertFoods(foods: List<NutritionFoodEntity>) = write { it.copy(foods = it.foods + foods) }

    override suspend fun setPriority(id: Long, priority: Int) = write { snapshot ->
        snapshot.copy(tables = snapshot.tables.map { if (it.id == id) it.copy(priority = priority) else it })
    }

    override suspend fun deleteTable(id: Long) = write { snapshot ->
        Snapshot(snapshot.tables.filter { it.id != id }, snapshot.foods.filter { it.tableId != id })
    }

    override suspend fun insertTableWithFoods(name: String, importedAt: Long, foods: List<NutritionFood>): Long =
        transaction { super.insertTableWithFoods(name, importedAt, foods) }

    override suspend fun setOrder(ids: List<Long>) = transaction { super.setOrder(ids) }

    private inline fun <T> transaction(block: () -> T): T {
        transactionDepth++
        try {
            return block().also { if (transactionDepth == 1) state.value = draft }
        } catch (e: Throwable) {
            draft = state.value // roll back
            throw e
        } finally {
            transactionDepth--
        }
    }

    private fun write(change: (Snapshot) -> Snapshot) {
        if (failWrites) throw IllegalStateException("disk full")
        draft = change(draft)
        if (transactionDepth == 0) state.value = draft
    }
}

/** The kcal per 100 g that the bundled usda.db holds for the ingredients of the en_breakfast oatmeal fixture. */
fun oatmealUsdaLookup(): NutritionLookup = TableLookup(
    IngredientMatcher(
        listOf(
            NutritionFood("rolled oats", listOf("oats"), 379.0),
            NutritionFood("whole milk", listOf("milk"), 61.0),
            NutritionFood("banana", emptyList(), 89.0),
            NutritionFood("honey", emptyList(), 304.0),
        ),
    ),
)
