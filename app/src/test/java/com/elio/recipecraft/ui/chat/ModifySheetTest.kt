package com.elio.recipecraft.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifySheetTest {

    @Test
    fun `a chip fills an empty instruction`() {
        assertEquals("Make it vegetarian", appendInstruction("  ", "Make it vegetarian", "; "))
    }

    @Test
    fun `a chip appends to what is typed, without doubling the separator`() {
        assertEquals("No nuts; Make it vegetarian", appendInstruction("No nuts ", "Make it vegetarian", "; "))
        assertEquals("No nuts; Cook it without an oven", appendInstruction("No nuts;", "Cook it without an oven", "; "))
        assertEquals("不要辣；改成素食", appendInstruction("不要辣；", "改成素食", "；"))
    }
}
