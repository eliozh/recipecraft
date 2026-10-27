package com.elio.recipecraft.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChatDao {

    /** Every conversation, the one with the latest message first. */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    abstract fun observeConversations(): Flow<List<ConversationEntity>>

    /** The conversation started or opened last, or null when there is none. */
    @Query("SELECT * FROM conversations ORDER BY openedAt DESC, id DESC LIMIT 1")
    abstract suspend fun lastOpened(): ConversationEntity?

    /** The messages of the conversation [conversationId], in order. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY position")
    abstract suspend fun messages(conversationId: Long): List<MessageEntity>

    @Insert
    abstract suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    abstract suspend fun setUpdatedAt(id: Long, at: Long)

    @Query("UPDATE conversations SET openedAt = :at WHERE id = :id")
    abstract suspend fun setOpenedAt(id: Long, at: Long)

    /** Adds a message, or replaces the one at its position. */
    @Upsert
    abstract suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND position = :position")
    abstract suspend fun deleteMessage(conversationId: Long, position: Long)

    /** Deletes a conversation; its messages go with it. */
    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteConversation(id: Long)

    /** Saves [message] and moves its conversation to the top of the recent list. */
    @Transaction
    open suspend fun saveMessage(message: MessageEntity, at: Long) {
        upsertMessage(message)
        setUpdatedAt(message.conversationId, at)
    }

    /** Starts a conversation with its first [message]; returns the new conversation's id. */
    @Transaction
    open suspend fun startConversation(conversation: ConversationEntity, message: MessageEntity): Long {
        val id = insertConversation(conversation)
        upsertMessage(message.copy(conversationId = id))
        return id
    }
}
