package com.elio.recipecraft.ui.chat

import com.elio.recipecraft.data.llm.RecipeParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyBufferTest {

    private val parser = RecipeParser()

    private val replies = listOf(
        "Try a tomato omelette.",
        "Here you go:\n```json\n{\"recipes\": []}\n```\nEnjoy!",
        "  \n {\"recipes\": [{\"name\": \"Oats\"}]}",
        "Some `code` and ``two`` ticks, then ```\n{}",
        "",
    )

    @Test
    fun `every split of a reply shows what the parser shows for the same text`() {
        for (reply in replies) {
            for (size in 1..4) {
                val buffer = ReplyBuffer(replyId = 1)
                reply.chunked(size).forEach { delta ->
                    buffer.append(delta)
                    val text = buffer.toString()
                    assertEquals("'$reply' in chunks of $size", text to parser.streaming(text), buffer.streaming())
                }
            }
        }
    }

    @Test
    fun `a fence split across deltas is found`() {
        val buffer = ReplyBuffer(replyId = 1)
        listOf("Here:\n`", "`", "`json\n{").forEach(buffer::append)
        assertEquals("Here:", buffer.streaming().second.prose)
        assertEquals(true, buffer.streaming().second.hasJson)
    }
}
