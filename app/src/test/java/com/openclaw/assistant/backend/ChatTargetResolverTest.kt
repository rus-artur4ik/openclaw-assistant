package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the two Chat code paths a turn takes.
 *
 * Every case is stated for both branches, because the failure this guards
 * against is a change that is only ever exercised by hand against one of them.
 */
class ChatTargetResolverTest {

    private fun backend(
        id: String,
        type: BackendType,
        primary: Boolean = false,
        enabled: Boolean = true,
        model: String? = null,
    ) = AgentBackendConfig(
        id = id,
        displayName = id,
        type = type,
        enabled = enabled,
        isPrimary = primary,
        baseUrl = "http://$id.test",
        modelName = model,
    )

    private val gateway = backend("gw", BackendType.OPENCLAW_GATEWAY, model = "openclaw")
    private val hermes = backend("hm", BackendType.HERMES_API_SERVER, model = "hermes-4")
    private val http = backend("api", BackendType.OPENCLAW_HTTP, model = "openclaw-api")

    // ---- branch selection ---------------------------------------------------

    @Test fun `primary gateway routes to the gateway runtime`() {
        val backends = listOf(gateway.copy(isPrimary = true), hermes)
        assertTrue(ChatTargetResolver.isGatewayTarget(useNodeChat = true, backends = backends, selectedId = null))
    }

    @Test fun `primary hermes routes to the dispatcher`() {
        val backends = listOf(gateway, hermes.copy(isPrimary = true))
        assertFalse(ChatTargetResolver.isGatewayTarget(useNodeChat = true, backends = backends, selectedId = null))
    }

    @Test fun `an explicit hermes selection beats a primary gateway`() {
        val backends = listOf(gateway.copy(isPrimary = true), hermes)
        assertFalse(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = "hm"))
    }

    @Test fun `an explicit gateway selection beats a primary hermes`() {
        val backends = listOf(gateway, hermes.copy(isPrimary = true))
        assertTrue(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = "gw"))
    }

    @Test fun `openclaw http is not the gateway runtime`() {
        // Both are "OpenClaw", but only the gateway has a NodeRuntime transcript;
        // treating the HTTP backend as one would strand the turn.
        val backends = listOf(http.copy(isPrimary = true))
        assertFalse(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = null))
    }

    @Test fun `the legacy setting only decides when no backends exist`() {
        assertTrue(ChatTargetResolver.isGatewayTarget(useNodeChat = true, backends = emptyList(), selectedId = null))
        assertFalse(ChatTargetResolver.isGatewayTarget(useNodeChat = false, backends = emptyList(), selectedId = null))
    }

    @Test fun `node chat off never routes to the gateway`() {
        val backends = listOf(gateway.copy(isPrimary = true))
        assertFalse(ChatTargetResolver.isGatewayTarget(useNodeChat = false, backends = backends, selectedId = "gw"))
    }

    @Test fun `all backends disabled is not the same as none configured`() {
        // Empty means "never onboarded" and defers to the legacy flag; disabled
        // means the user switched everything off and nothing should be sent.
        val backends = listOf(gateway.copy(isPrimary = true, enabled = false))
        assertFalse(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = null))
        assertNull(ChatTargetResolver.resolveTarget(backends, selectedId = null))
    }

    // ---- stale selections ---------------------------------------------------

    @Test fun `a selection naming a deleted backend falls back to primary`() {
        val backends = listOf(gateway.copy(isPrimary = true), hermes)
        assertEquals("gw", ChatTargetResolver.resolveTarget(backends, selectedId = "deleted")?.id)
        assertTrue(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = "deleted"))
    }

    @Test fun `a selection naming a disabled backend falls back to primary`() {
        // Disabling the selected Hermes backend used to leave the routing
        // decision on Primary while the send addressed the disabled id, which
        // failed the turn with "no backend selected".
        val backends = listOf(gateway.copy(isPrimary = true), hermes.copy(enabled = false))
        val resolved = ChatTargetResolver.resolveTarget(backends, selectedId = "hm")
        assertEquals("gw", resolved?.id)
        assertTrue(ChatTargetResolver.isGatewayTarget(true, backends, selectedId = "hm"))
    }

    @Test fun `routing and model resolution agree on the same target`() {
        val backends = listOf(gateway.copy(isPrimary = true), hermes.copy(enabled = false))
        val resolved = ChatTargetResolver.resolveTarget(backends, selectedId = "hm")
        val gatewayBranch = ChatTargetResolver.isGatewayTarget(true, backends, selectedId = "hm")
        val model = ChatTargetResolver.openClawModelOverride(backends, selectedId = "hm")
        assertEquals(BackendType.OPENCLAW_GATEWAY, resolved?.type)
        assertTrue(gatewayBranch)
        assertEquals("openclaw", model)
    }

    // ---- model override -----------------------------------------------------

    @Test fun `gateway model is sent to the gateway`() {
        assertEquals("openclaw", ChatTargetResolver.openClawModelOverride(listOf(gateway.copy(isPrimary = true)), null))
    }

    @Test fun `openclaw http model is sent too`() {
        assertEquals("openclaw-api", ChatTargetResolver.openClawModelOverride(listOf(http.copy(isPrimary = true)), null))
    }

    @Test fun `hermes model is not sent through the openclaw override`() {
        // Hermes needs model and provider together; leaking the bare model into
        // the OpenClaw path would send the wrong field to the wrong server.
        assertNull(ChatTargetResolver.openClawModelOverride(listOf(hermes.copy(isPrimary = true)), null))
    }

    @Test fun `a blank model is treated as unset`() {
        val blank = gateway.copy(isPrimary = true, modelName = "   ")
        assertNull(ChatTargetResolver.openClawModelOverride(listOf(blank), null))
    }

    // ---- history ------------------------------------------------------------

    @Test fun `history keeps the newest turns and drops blanks`() {
        val messages = (1..50).map { (it % 2 == 1) to "m$it" } + listOf(true to "   ")
        val trimmed = ChatTargetResolver.trimHistory(messages, cap = 40)
        assertEquals(40, trimmed.size)
        assertEquals("m11", trimmed.first().content)
        assertEquals("m50", trimmed.last().content)
    }

    @Test fun `history preserves roles`() {
        val trimmed = ChatTargetResolver.trimHistory(listOf(true to "q", false to "a"), cap = 10)
        assertEquals(listOf("user", "assistant"), trimmed.map { it.role })
    }

    @Test fun `an empty transcript yields no history`() {
        assertTrue(ChatTargetResolver.trimHistory(emptyList(), cap = 40).isEmpty())
    }
}
