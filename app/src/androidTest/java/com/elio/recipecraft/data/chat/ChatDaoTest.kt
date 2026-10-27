package com.elio.recipecraft.data.chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real Room DAO and ChatRepository on an in-memory [AppDatabase]. */
@RunWith(AndroidJUnit4::class)
class ChatDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ChatDao

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.chats()
    }

    @After
    fun close() = database.close()

    private fun conversation(title: String, at: Long) =
        ConversationEntity(title = title, firstRequestJson = null, createdAt = at, updatedAt = at, openedAt = at)

    private fun message(position: Long, text: String, conversationId: Long = 0) =
        ChatMessage(position, if (position % 2 == 0L) Role.USER else Role.ASSISTANT, text).toEntity(conversationId)

    private fun repository(at: Long) =
        ChatRepository(dao, Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC), Dispatchers.IO)

    @Test
    fun messagesComeBackInPositionOrderAndSavingAgainReplaces() = runBlocking {
        val id = dao.startConversation(conversation("Hi", 1), message(0, "Hi"))
        dao.saveMessage(message(2, "Later", id), at = 3)
        dao.saveMessage(message(1, "Hel", id), at = 2)
        dao.saveMessage(message(1, "Hello", id), at = 4)

        assertEquals(listOf("Hi", "Hello", "Later"), dao.messages(id).map { it.text })
        assertEquals(4, dao.observeConversations().first().single().updatedAt)
    }

    @Test
    fun conversationsAreListedLatestMessageFirstAndTheLastOpenedIsRestored() = runBlocking {
        assertNull(dao.lastOpened())
        val older = dao.startConversation(conversation("Older", 1), message(0, "Older"))
        val newer = dao.startConversation(conversation("Newer", 2), message(0, "Newer"))
        assertEquals(listOf(newer, older), dao.observeConversations().first().map { it.id })
        assertEquals(newer, dao.lastOpened()?.id)

        dao.saveMessage(message(1, "Reply", older), at = 5)
        dao.setOpenedAt(older, 6)
        assertEquals(listOf(older, newer), dao.observeConversations().first().map { it.id })
        assertEquals(older, dao.lastOpened()?.id)
    }

    @Test
    fun deletingAConversationDeletesItsMessagesOnly() = runBlocking {
        val gone = dao.startConversation(conversation("Gone", 1), message(0, "Gone"))
        dao.saveMessage(message(1, "Reply", gone), at = 2)
        val kept = dao.startConversation(conversation("Kept", 3), message(0, "Kept"))

        dao.deleteConversation(gone)

        assertEquals(listOf(kept), dao.observeConversations().first().map { it.id })
        assertTrue(dao.messages(gone).isEmpty())
        assertEquals(listOf("Kept"), dao.messages(kept).map { it.text })
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun deleteMessageRemovesOneMessage() = runBlocking {
        val id = dao.startConversation(conversation("Hi", 1), message(0, "Hi"))
        dao.saveMessage(message(1, "Bad reply", id), at = 2)
        dao.deleteMessage(id, 1)
        assertEquals(listOf("Hi"), dao.messages(id).map { it.text })
    }

    @Test
    fun aReplyWithRecipesSurvivesTheDatabaseThroughTheRepository() = runBlocking {
        val recipe = Recipe(
            id = "r1",
            name = "番茄炒蛋",
            mealType = MealType.LUNCH,
            servings = 2,
            calories = Calories(250, 125, CalorieSource.DATABASE, 300),
            ingredients = listOf(Ingredient("鸡蛋", "egg", "2", "个", 100.0, 143), Ingredient("盐", "salt", "适量", "", null, null)),
            tools = listOf("锅"),
            steps = listOf(Step(1, "炒", 5), Step(2, "装盘", null)),
            language = Language.ZH,
            setId = "set-1",
            createdAt = 10,
            updatedAt = 20,
        )
        val repository = repository(at = 100)
        val id = repository.start(ChatMessage(0, Role.USER, "午饭"))
        val reply = ChatMessage(1, Role.ASSISTANT, "好的", recipes = listOf(recipe), replyState = ReplyState.DONE)
        repository.save(id, reply)
        repository.save(id, ChatMessage(2, Role.USER, "再来"))
        repository.save(id, ChatMessage(3, Role.ASSISTANT, "部分", replyState = ReplyState.STREAMING))

        val messages = repository(at = 200).open(id)
        assertEquals(reply, messages[1])
        assertEquals(ReplyState.STOPPED, messages[3].replyState)
        assertEquals("部分", messages[3].text)
        assertEquals(200L, dao.lastOpened()?.openedAt)
    }
}
