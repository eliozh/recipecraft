package com.elio.recipecraft.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {

    @Test
    fun `built-in defaults use the documented endpoints and models`() {
        assertEquals(
            ProviderConfig(ProviderType.CLAUDE, "https://api.anthropic.com", Protocol.ANTHROPIC, "claude-sonnet-5-5"),
            ProviderDefaults.config(ProviderType.CLAUDE),
        )
        assertEquals(
            ProviderConfig(ProviderType.OPENAI, "https://api.openai.com/v1", Protocol.OPENAI_COMPATIBLE, ""),
            ProviderDefaults.config(ProviderType.OPENAI),
        )
        assertEquals(
            ProviderConfig(ProviderType.DEEPSEEK, "https://api.deepseek.com", Protocol.OPENAI_COMPATIBLE, "deepseek-chat"),
            ProviderDefaults.config(ProviderType.DEEPSEEK),
        )
    }

    @Test
    fun `only custom has an editable endpoint`() {
        assertTrue(ProviderDefaults.hasEditableEndpoint(ProviderType.CUSTOM))
        ProviderType.entries.filter { it != ProviderType.CUSTOM }
            .forEach { assertFalse(ProviderDefaults.hasEditableEndpoint(it)) }
    }

    @Test
    fun `built-in defaults with a model are ready`() {
        assertEquals(emptySet<ConfigProblem>(), ProviderDefaults.config(ProviderType.CLAUDE).problems())
        assertEquals(emptySet<ConfigProblem>(), ProviderDefaults.config(ProviderType.DEEPSEEK).problems())
    }

    @Test
    fun `openai without a model is blocked`() {
        assertEquals(setOf(ConfigProblem.MISSING_MODEL), ProviderDefaults.config(ProviderType.OPENAI).problems())
    }

    @Test
    fun `blank custom endpoint needs a base url and a model`() {
        assertEquals(
            setOf(ConfigProblem.MISSING_MODEL, ConfigProblem.MISSING_BASE_URL),
            ProviderDefaults.config(ProviderType.CUSTOM).copy(baseUrl = "  ", model = " ").problems(),
        )
    }

    @Test
    fun `base url must be an absolute url`() {
        val custom = ProviderConfig(ProviderType.CUSTOM, "", Protocol.OPENAI_COMPATIBLE, "m")
        listOf("api.example.com", "https://", "http://exa mple.com", "https:///v1").forEach {
            assertEquals(it, setOf(ConfigProblem.INVALID_BASE_URL), custom.copy(baseUrl = it).problems())
        }
    }

    @Test
    fun `base url must use https`() {
        val custom = ProviderConfig(ProviderType.CUSTOM, "", Protocol.OPENAI_COMPATIBLE, "m")
        listOf("http://api.example.com/v1", " http://192.168.1.5:11434 ", "HTTP://Example.com", "ftp://example.com")
            .forEach {
                assertEquals(it, setOf(ConfigProblem.INSECURE_BASE_URL), custom.copy(baseUrl = it).problems())
            }
        listOf("https://api.example.com/v1", " https://192.168.1.5:11434 ", "HTTPS://Example.com").forEach {
            assertEquals(it, emptySet<ConfigProblem>(), custom.copy(baseUrl = it).problems())
        }
    }
}
