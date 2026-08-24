package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session list's product badge and reopen target.
 *
 * A thread that reopens against the wrong backend sends its next message to the
 * other product, so both branches are asserted for every rule.
 */
class SessionProductResolverTest {

    private fun backend(id: String, type: BackendType, primary: Boolean = false, enabled: Boolean = true) =
        AgentBackendConfig(
            id = id,
            displayName = id,
            type = type,
            enabled = enabled,
            isPrimary = primary,
            baseUrl = "http://$id.test",
        )

    private val gateway = backend("gw", BackendType.OPENCLAW_GATEWAY)
    private val hermes = backend("hm", BackendType.HERMES_API_SERVER)
    private val hermes2 = backend("hm2", BackendType.HERMES_API_SERVER)
    private val http = backend("api", BackendType.OPENCLAW_HTTP)

    // ---- badge --------------------------------------------------------------

    @Test fun `an explicit hermes tag is honoured`() {
        assertEquals(
            SessionProduct.HERMES,
            SessionProductResolver.badgeFor(listOf(gateway.copy(isPrimary = true)), SessionProduct.HERMES, null, false),
        )
    }

    @Test fun `an explicit openclaw tag is honoured`() {
        assertEquals(
            SessionProduct.OPENCLAW,
            SessionProductResolver.badgeFor(listOf(hermes.copy(isPrimary = true)), SessionProduct.OPENCLAW, null, false),
        )
    }

    @Test fun `an untagged thread follows its stored backend`() {
        assertEquals(
            SessionProduct.HERMES,
            SessionProductResolver.badgeFor(listOf(gateway.copy(isPrimary = true), hermes), null, "hm", true),
        )
    }

    @Test fun `an untagged gateway-backed thread stays openclaw`() {
        assertEquals(
            SessionProduct.OPENCLAW,
            SessionProductResolver.badgeFor(listOf(hermes.copy(isPrimary = true), gateway), null, "gw", true),
        )
    }

    @Test fun `a thread whose backend was deleted falls back to primary`() {
        assertEquals(
            SessionProduct.HERMES,
            SessionProductResolver.badgeFor(listOf(hermes.copy(isPrimary = true)), null, "gone", true),
        )
    }

    @Test fun `a legacy thread on a hermes-only install is labelled hermes`() {
        assertEquals(
            SessionProduct.HERMES,
            SessionProductResolver.badgeFor(listOf(hermes.copy(isPrimary = true)), null, null, false),
        )
    }

    @Test fun `a legacy thread stays openclaw when an openclaw http backend exists`() {
        assertEquals(
            SessionProduct.OPENCLAW,
            SessionProductResolver.badgeFor(listOf(hermes.copy(isPrimary = true), http), null, null, false),
        )
    }

    @Test fun `a gateway-only install labels legacy threads openclaw`() {
        assertEquals(
            SessionProduct.OPENCLAW,
            SessionProductResolver.badgeFor(listOf(gateway.copy(isPrimary = true)), null, null, false),
        )
    }

    @Test fun `no backends at all still yields a badge`() {
        assertEquals(SessionProduct.OPENCLAW, SessionProductResolver.badgeFor(emptyList(), null, null, false))
    }

    // ---- reopen target ------------------------------------------------------

    @Test fun `a gateway thread reopens on the gateway backend not on primary`() {
        // Returning null here would mean "follow Primary", which on this install
        // is Hermes — the thread would then answer from the wrong product.
        val backends = listOf(hermes.copy(isPrimary = true), gateway)
        assertEquals("gw", SessionProductResolver.chatTargetFor(backends, isGateway = true, storedBackendId = null))
    }

    @Test fun `a gateway thread with no gateway backend has no target`() {
        assertNull(SessionProductResolver.chatTargetFor(listOf(hermes.copy(isPrimary = true)), true, null))
    }

    @Test fun `a local thread reopens on the backend it was created against`() {
        val backends = listOf(hermes.copy(isPrimary = true), hermes2)
        assertEquals("hm2", SessionProductResolver.chatTargetFor(backends, isGateway = false, storedBackendId = "hm2"))
    }

    @Test fun `two hermes backends are told apart`() {
        // The product tag alone cannot distinguish them; the stored id must.
        val backends = listOf(hermes.copy(isPrimary = true), hermes2)
        assertEquals("hm", SessionProductResolver.chatTargetFor(backends, false, "hm"))
        assertEquals("hm2", SessionProductResolver.chatTargetFor(backends, false, "hm2"))
    }

    @Test fun `a disabled stored backend is not selected`() {
        val backends = listOf(hermes.copy(isPrimary = true), hermes2.copy(enabled = false))
        assertNull(SessionProductResolver.chatTargetFor(backends, false, "hm2"))
        assertNull(SessionProductResolver.storedBackendId(backends, "hm2"))
    }

    @Test fun `an untracked local thread follows primary`() {
        assertNull(SessionProductResolver.chatTargetFor(listOf(hermes.copy(isPrimary = true)), false, null))
    }

    // ---- product of a type --------------------------------------------------

    @Test fun `both openclaw backend types map to the openclaw product`() {
        assertEquals(SessionProduct.OPENCLAW, SessionProductResolver.productOf(BackendType.OPENCLAW_GATEWAY))
        assertEquals(SessionProduct.OPENCLAW, SessionProductResolver.productOf(BackendType.OPENCLAW_HTTP))
        assertEquals(SessionProduct.HERMES, SessionProductResolver.productOf(BackendType.HERMES_API_SERVER))
        assertEquals(SessionProduct.OPENCLAW, SessionProductResolver.productOf(null))
    }
}
