package com.elio.recipecraft.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.elio.recipecraft.data.llm.LlmMessage

/** One chat. */
@Entity(tableName = "conversations", indices = [Index("updatedAt"), Index("openedAt")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The start of the first user turn; empty when that turn came from the composer. */
    val title: String,
    /** The composer request of the first user turn, as JSON, so the title can be shown in the app's current language. */
    val firstRequestJson: String?,
    val createdAt: Long,
    /** When the last message was saved; the recent chats list is newest first by this. */
    val updatedAt: Long,
    /** When the chat was last started or opened; the app reopens the latest on launch. */
    val openedAt: Long,
)

/**
 * One message of a conversation. [position] is the message id the chat uses, so a message is
 * saved again over itself as it changes. The JSON columns follow the `Stored*` classes in
 * [ChatJson], so renaming a domain property cannot silently change what is on disk.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["conversationId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    val conversationId: Long,
    val position: Long,
    val role: LlmMessage.Role,
    val text: String,
    val shownText: String,
    val recipesJson: String,
    val recipeStatus: RecipeStatus,
    val status: ReplyState,
    val generationJson: String?,
    val shortfallJson: String?,
)
