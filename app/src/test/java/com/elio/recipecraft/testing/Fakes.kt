package com.elio.recipecraft.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.elio.recipecraft.data.chat.ChatDao
import com.elio.recipecraft.data.chat.ConversationEntity
import com.elio.recipecraft.data.chat.MessageEntity
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
import com.elio.recipecraft.data.recipe.RecipeDocuments
import com.elio.recipecraft.data.recipe.RecipeEntity
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

    override fun observeById(id: String): Flow<RecipeEntity?> = readable().map { all -> all.firstOrNull { it.id == id } }

    override fun observeIds(): Flow<List<String>> = readable().map { all -> all.map { it.id } }

    override fun observeCount(): Flow<Int> = readable().map { it.size }

    override suspend fun all(): List<RecipeEntity> {
        if (failReads) throw IllegalStateException("database unreadable")
        return state.value
    }

    override suspend fun byIds(ids: List<String>): List<RecipeEntity> = all().filter { it.id in ids }

    override suspend fun notIn(language: Language): List<RecipeEntity> =
        all().withIndex()
            .sortedWith(compareByDescending<IndexedValue<RecipeEntity>> { it.value.createdAt }.thenBy { it.index })
            .map { it.value }
            .filter { it.language != language }

    override suspend fun update(recipe: RecipeEntity): Int {
        if (state.value.none { it.id == recipe.id }) return 0
        write { all -> all.map { if (it.id == recipe.id) recipe else it } }
        return 1
    }

    override suspend fun upsert(recipes: List<RecipeEntity>) = write { all ->
        recipes.fold(all) { acc, recipe ->
            if (acc.any { it.id == recipe.id }) acc.map { if (it.id == recipe.id) recipe else it } else acc + recipe
        }
    }

    override suspend fun insertNew(recipes: List<RecipeEntity>): List<Long> {
        val rowIds = mutableListOf<Long>()
        write { all ->
            recipes.fold(all) { acc, recipe ->
                if (acc.any { it.id == recipe.id }) {
                    rowIds += -1L
                    acc
                } else {
                    rowIds += acc.size + 1L
                    acc + recipe
                }
            }
        }
        return rowIds
    }

    override suspend fun deleteById(id: String) = write { all -> all.filter { it.id != id } }

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
 * An in-memory [ChatDao]. It inherits the real transaction methods; deleting a conversation
 * deletes its messages, as the foreign key does.
 */
class FakeChatDao : ChatDao() {
    private val conversationState = MutableStateFlow<List<ConversationEntity>>(emptyList())
    private var nextId = 1L

    val conversations: List<ConversationEntity> get() = conversationState.value
    val messages = mutableListOf<MessageEntity>()
    var failWrites = false

    override fun observeConversations(): Flow<List<ConversationEntity>> =
        conversationState.map { all -> all.sortedWith(compareByDescending<ConversationEntity> { it.updatedAt }.thenByDescending { it.id }) }

    override suspend fun lastOpened(): ConversationEntity? =
        conversations.sortedWith(compareByDescending<ConversationEntity> { it.openedAt }.thenByDescending { it.id }).firstOrNull()

    override suspend fun messages(conversationId: Long): List<MessageEntity> =
        messages.filter { it.conversationId == conversationId }.sortedBy { it.position }

    override suspend fun insertConversation(conversation: ConversationEntity): Long {
        checkWritable()
        val id = nextId++
        conversationState.value += conversation.copy(id = id)
        return id
    }

    override suspend fun setUpdatedAt(id: Long, at: Long) = updateConversation(id) { it.copy(updatedAt = at) }

    override suspend fun setOpenedAt(id: Long, at: Long) = updateConversation(id) { it.copy(openedAt = at) }

    override suspend fun upsertMessage(message: MessageEntity) {
        checkWritable()
        check(conversations.any { it.id == message.conversationId }) { "FOREIGN KEY constraint failed" }
        val index = messages.indexOfFirst { it.conversationId == message.conversationId && it.position == message.position }
        if (index >= 0) messages[index] = message else messages += message
    }

    override suspend fun deleteMessage(conversationId: Long, position: Long) {
        checkWritable()
        messages.removeAll { it.conversationId == conversationId && it.position == position }
    }

    override suspend fun deleteConversation(id: Long) {
        checkWritable()
        conversationState.value = conversations.filter { it.id != id }
        messages.removeAll { it.conversationId == id }
    }

    private fun updateConversation(id: Long, change: (ConversationEntity) -> ConversationEntity) {
        checkWritable()
        conversationState.value = conversations.map { if (it.id == id) change(it) else it }
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("disk full")
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

/** A UTC clock the test moves with [instant]. */
class MutableClock(var instant: Instant) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/** Documents held in memory by URI; writing one replaces what it held once the stream closes. */
class FakeRecipeDocuments : RecipeDocuments {
    val files = mutableMapOf<String, ByteArray>()

    override fun openInput(uri: String): InputStream = ByteArrayInputStream(files[uri] ?: throw FileNotFoundException(uri))

    override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            files[uri] = toByteArray()
        }
    }
}
