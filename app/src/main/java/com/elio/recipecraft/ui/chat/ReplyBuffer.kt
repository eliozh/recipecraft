package com.elio.recipecraft.ui.chat

import com.elio.recipecraft.data.llm.RecipeParser
import com.elio.recipecraft.data.llm.StreamingReply

/**
 * The text of the reply [replyId] as it streams in. Each delta is appended in place and only the
 * new text is searched for the JSON fence, so a long reply costs time in proportion to its
 * length rather than to its length squared. [streaming] gives what [RecipeParser.streaming] gives
 * for the same text.
 */
class ReplyBuffer(val replyId: Long) {
    private val text = StringBuilder()

    /** Where the JSON fence starts, or -1 until one has arrived. */
    private var fence = -1

    /** The first character that is not whitespace, or null while there is none. */
    private var firstVisible: Char? = null

    fun append(delta: String) {
        val from = text.length
        text.append(delta)
        if (firstVisible == null) firstVisible = delta.firstOrNull { !it.isWhitespace() }
        // A fence can be split across deltas, so the search starts a little before the new text.
        if (fence < 0) fence = text.indexOf(FENCE, (from - FENCE.length + 1).coerceAtLeast(0))
    }

    override fun toString(): String = text.toString()

    /** The text so far, and what the bubble shows of it. */
    fun streaming(): Pair<String, StreamingReply> {
        val all = text.toString()
        val shown = when {
            fence >= 0 -> StreamingReply(all.substring(0, fence).trim(), hasJson = true)
            firstVisible == '{' -> StreamingReply("", hasJson = true)
            else -> StreamingReply(all, hasJson = false)
        }
        return all to shown
    }

    private companion object {
        const val FENCE = "```"
    }
}
