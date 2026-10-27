package com.elio.recipecraft.data.chat

import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.di.IoDispatcher
import com.elio.recipecraft.domain.GenerationRequest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A chat as the recent chats list shows it. */
data class Conversation(
    val id: Long,
    /** The start of the first user turn; empty when [firstRequest] is set. */
    val title: String,
    /** The composer request of the first user turn, which the list summarizes in the current language. */
    val firstRequest: GenerationRequest?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The chats kept on the device, so a conversation survives the app being stopped. */
@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Every conversation, the one with the latest message first. */
    val conversations: Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    /** The id of the conversation started or opened last, or null when there is none. */
    suspend fun lastOpened(): Long? = dao.lastOpened()?.id

    /**
     * Marks the conversation [id] as the open one and returns its messages. A reply that was still
     * streaming when the app stopped comes back as stopped, with the text that had arrived.
     */
    suspend fun open(id: Long): List<ChatMessage> {
        dao.setOpenedAt(id, clock.millis())
        val entities = dao.messages(id)
        return withContext(ioDispatcher) { entities.mapNotNull { it.toDomain().restored() } }
    }

    /** Starts a conversation with its first user turn, titled after it; returns the conversation's id. */
    suspend fun start(first: ChatMessage): Long {
        val now = clock.millis()
        val conversation = ConversationEntity(
            title = if (first.generation == null) titleOf(first.text) else "",
            firstRequestJson = ChatJson.requestToJson(first.generation),
            createdAt = now,
            updatedAt = now,
            openedAt = now,
        )
        val message = withContext(ioDispatcher) { first.toEntity(conversationId = 0) }
        return dao.startConversation(conversation, message)
    }

    /** Saves [message] in the conversation [conversationId], over any earlier version of it. */
    suspend fun save(conversationId: Long, message: ChatMessage) {
        val entity = withContext(ioDispatcher) { message.toEntity(conversationId) }
        dao.saveMessage(entity, clock.millis())
    }

    suspend fun deleteMessage(conversationId: Long, messageId: Long) = dao.deleteMessage(conversationId, messageId)

    /** Deletes the conversation [id] and its messages. */
    suspend fun delete(id: Long) = dao.deleteConversation(id)

    companion object {
        const val TITLE_LENGTH = 60

        /** The first [TITLE_LENGTH] characters of [text] on one line, with an ellipsis when cut. */
        fun titleOf(text: String): String {
            val line = text.trim().replace(Regex("\\s+"), " ")
            return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH - 1).trimEnd() + "…"
        }
    }
}

internal fun ConversationEntity.toDomain() =
    Conversation(id, title, ChatJson.requestFromJson(firstRequestJson), createdAt, updatedAt)

internal fun ChatMessage.toEntity(conversationId: Long) = MessageEntity(
    conversationId = conversationId,
    position = id,
    role = role,
    text = text,
    shownText = shownText,
    recipesJson = ChatJson.recipesToJson(recipes),
    recipeStatus = recipeStatus,
    status = replyState,
    generationJson = ChatJson.requestToJson(generation),
    shortfallJson = ChatJson.shortfallToJson(shortfall),
)

internal fun MessageEntity.toDomain() = ChatMessage(
    id = position,
    role = role,
    text = text,
    shownText = shownText,
    recipes = ChatJson.recipesFromJson(recipesJson),
    recipeStatus = recipeStatus,
    generation = ChatJson.requestFromJson(generationJson),
    shortfall = ChatJson.shortfallFromJson(shortfallJson),
    replyState = status,
)

/** A stored message as the chat shows it again: a reply cut off mid-stream is stopped, and dropped when it is empty. */
internal fun ChatMessage.restored(): ChatMessage? = when {
    replyState != ReplyState.STREAMING -> this
    role == LlmMessage.Role.ASSISTANT && text.isNotEmpty() -> cutOff(ReplyState.STOPPED)
    else -> null
}
