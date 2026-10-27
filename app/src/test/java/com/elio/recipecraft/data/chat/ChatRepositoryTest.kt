package com.elio.recipecraft.data.chat

import com.elio.recipecraft.data.llm.LlmMessage.Role
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.domain.GenerationShortfall
import com.elio.recipecraft.domain.IncompleteSet
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.testing.FakeChatDao
import com.elio.recipecraft.testing.MutableClock
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.testRecipe
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {

    private val dao = FakeChatDao()
    private val clock = MutableClock(Instant.ofEpochMilli(1_000))
    private val repository = ChatRepository(dao, clock, UnconfinedTestDispatcher())

    private val recipes = listOf(
        testRecipe(
            "a",
            name = "番茄炒蛋",
            mealType = null,
            ingredients = listOf(ingredient("鸡蛋", "egg"), ingredient("番茄", "tomato", grams = null)),
            language = Language.ZH,
            setId = "set-1",
        ).copy(calories = Calories(250, 125, CalorieSource.DATABASE, 300)),
        testRecipe("b", mealType = MealType.DINNER, aiTotal = null),
    )

    private fun tick(millis: Long = 1_000) {
        clock.instant = clock.instant.plusMillis(millis)
    }

    @Test
    fun `a message survives the trip to an entity and back, recipes and all`() {
        val reply = ChatMessage(
            id = 7,
            role = Role.ASSISTANT,
            text = "Here you go.\n```json\n{}\n```",
            shownText = "Here you go.",
            recipes = recipes,
            shortfall = GenerationShortfall(2, 1, sets = true, incomplete = listOf(IncompleteSet(1, listOf(MealType.LUNCH)))),
        )
        val entity = reply.toEntity(conversationId = 3)

        assertEquals(3, entity.conversationId)
        assertEquals(7, entity.position)
        assertEquals(reply, entity.toDomain())
    }

    @Test
    fun `a composer turn keeps its request`() {
        val user = ChatMessage(1, Role.USER, "template", shownText = "", generation = GenerationRequest(GenerationScope.DINNER, 3, "quick"))
        assertEquals(user, user.toEntity(1).toDomain())
    }

    @Test
    fun `every recipe status and reply state is kept`() {
        RecipeStatus.entries.forEach { status ->
            ReplyState.entries.forEach { state ->
                val message = ChatMessage(1, Role.ASSISTANT, "x", recipeStatus = status, replyState = state)
                assertEquals(message, message.toEntity(1).toDomain())
            }
        }
    }

    @Test
    fun `a conversation is titled after its first turn and listed with the latest first`() = runTest {
        val first = repository.start(ChatMessage(0, Role.USER, "  Something\nwith   eggs  "))
        tick()
        val second = repository.start(
            ChatMessage(0, Role.USER, "template", shownText = "", generation = GenerationRequest(GenerationScope.FULL_DAY_SET, 2)),
        )

        val list = repository.conversations.first()
        assertEquals(listOf(second, first), list.map { it.id })
        assertEquals("", list[0].title)
        assertEquals(GenerationRequest(GenerationScope.FULL_DAY_SET, 2), list[0].firstRequest)
        assertEquals("Something with eggs", list[1].title)
        assertNull(list[1].firstRequest)

        tick()
        repository.save(first, ChatMessage(1, Role.ASSISTANT, "Sure."))
        assertEquals(listOf(first, second), repository.conversations.first().map { it.id })
        assertEquals(clock.millis(), repository.conversations.first()[0].updatedAt)
    }

    @Test
    fun `a long first turn is cut to the title length with an ellipsis`() {
        val title = ChatRepository.titleOf("a".repeat(100))
        assertEquals(ChatRepository.TITLE_LENGTH, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals("short", ChatRepository.titleOf("short"))
    }

    @Test
    fun `saving a message again replaces it, and messages come back in order`() = runTest {
        val id = repository.start(ChatMessage(0, Role.USER, "Hi"))
        repository.save(id, ChatMessage(1, Role.ASSISTANT, "Hel", replyState = ReplyState.STREAMING))
        repository.save(id, ChatMessage(1, Role.ASSISTANT, "Hello", recipes = recipes))
        repository.save(id, ChatMessage(2, Role.USER, "More"))

        val messages = repository.open(id)
        assertEquals(listOf(0L, 1L, 2L), messages.map { it.id })
        assertEquals("Hello", messages[1].text)
        assertEquals(recipes, messages[1].recipes)
        assertEquals(ReplyState.DONE, messages[1].replyState)
    }

    @Test
    fun `the last opened conversation is the one to restore`() = runTest {
        assertNull(repository.lastOpened())
        val first = repository.start(ChatMessage(0, Role.USER, "One"))
        tick()
        val second = repository.start(ChatMessage(0, Role.USER, "Two"))
        assertEquals(second, repository.lastOpened())

        tick()
        repository.open(first)
        assertEquals(first, repository.lastOpened())
    }

    @Test
    fun `a reply cut off by the app stopping comes back stopped with its partial text`() = runTest {
        val id = repository.start(ChatMessage(0, Role.USER, "Hi"))
        repository.save(id, ChatMessage(1, Role.ASSISTANT, "Try oat", replyState = ReplyState.STREAMING))

        val reply = repository.open(id)[1]
        assertEquals("Try oat", reply.text)
        assertEquals(ReplyState.STOPPED, reply.replyState)
        assertEquals(RecipeStatus.NONE, reply.recipeStatus)
    }

    @Test
    fun `a reply cut off while its recipes were coming shows its raw text to retry`() = runTest {
        val id = repository.start(ChatMessage(0, Role.USER, "Hi"))
        val text = "Here.\n```json\n{\"recipes\": ["
        repository.save(id, ChatMessage(1, Role.ASSISTANT, text, shownText = "Here.", recipeStatus = RecipeStatus.BUILDING, replyState = ReplyState.STREAMING))

        val reply = repository.open(id)[1]
        assertEquals(text, reply.shownText)
        assertEquals(RecipeStatus.FAILED, reply.recipeStatus)
        assertEquals(ReplyState.STOPPED, reply.replyState)
    }

    @Test
    fun `an empty cut-off reply is dropped, while stopped and failed replies come back as they were`() = runTest {
        val id = repository.start(ChatMessage(0, Role.USER, "Hi"))
        repository.save(id, ChatMessage(1, Role.ASSISTANT, "Par", replyState = ReplyState.STOPPED))
        repository.save(id, ChatMessage(2, Role.USER, "Again"))
        repository.save(id, ChatMessage(3, Role.ASSISTANT, "Err", replyState = ReplyState.ERROR))
        repository.save(id, ChatMessage(4, Role.ASSISTANT, "", replyState = ReplyState.STREAMING))

        val messages = repository.open(id)
        assertEquals(listOf(0L, 1L, 2L, 3L), messages.map { it.id })
        assertEquals(ReplyState.STOPPED, messages[1].replyState)
        assertEquals(ReplyState.ERROR, messages[3].replyState)
    }

    @Test
    fun `deleting a message or a conversation removes it`() = runTest {
        val id = repository.start(ChatMessage(0, Role.USER, "Hi"))
        repository.save(id, ChatMessage(1, Role.ASSISTANT, "Hello"))
        repository.deleteMessage(id, 1)
        assertEquals(listOf(0L), repository.open(id).map { it.id })

        repository.delete(id)
        assertTrue(repository.conversations.first().isEmpty())
        assertTrue(dao.messages.isEmpty())
    }
}
