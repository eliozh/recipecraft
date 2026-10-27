package com.elio.recipecraft.data.chat

import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationShortfall
import com.elio.recipecraft.domain.Recipe

/** How far an assistant reply has got with turning its JSON into recipes. */
enum class RecipeStatus {
    /** Plain text, or recipes already parsed. */
    NONE,

    /** JSON is streaming in; the bubble shows only the prose. */
    BUILDING,

    /** The JSON did not parse and the one automatic repair turn is running. */
    REPAIRING,

    /** Neither the reply nor the repair parsed; the raw text is shown with Retry. */
    FAILED,
}

/** How a reply's stream ended. A user turn is always [DONE]. */
enum class ReplyState {
    DONE,

    /** Still receiving deltas; a reply stored in this state was cut off by the app stopping. */
    STREAMING,

    /** The user pressed Stop; the text is what had arrived. */
    STOPPED,

    /** The stream ended with an error; the text is what had arrived. */
    ERROR,
}

data class ChatMessage(
    /** The message's place in its conversation; later messages have larger ids. */
    val id: Long,
    val role: LlmMessage.Role,
    /** The raw text, sent back to the model as history. */
    val text: String,
    /** What the bubble shows: the prose without the JSON block while recipes are involved. */
    val shownText: String = text,
    val recipes: List<Recipe> = emptyList(),
    val recipeStatus: RecipeStatus = RecipeStatus.NONE,
    /** On a user turn sent from the composer: what it asked for, which the bubble summarizes. */
    val generation: GenerationRequest? = null,
    /** On a reply to the composer: how it fell short of the request, or null when it did not. */
    val shortfall: GenerationShortfall? = null,
    val replyState: ReplyState = ReplyState.DONE,
) {
    /**
     * This reply as it ends when its stream stops before finishing: a reply cut off while its
     * recipes were still coming shows its raw text, which can only be retried.
     */
    fun cutOff(state: ReplyState): ChatMessage =
        if (recipeStatus == RecipeStatus.NONE) {
            copy(replyState = state)
        } else {
            copy(shownText = text, recipeStatus = RecipeStatus.FAILED, replyState = state)
        }
}
