package com.openclaw.assistant.backend

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persisted backend configuration, including how installs written before the
 * transport picker existed are read back.
 *
 * A migration that silently changes a Hermes backend's transport changes which
 * endpoints the app talks to — and that only shows up when someone actually
 * upgrades, which no amount of hand-testing a fresh install will find.
 */
class AgentBackendConfigTest {

    private val json = BackendRepository.DEFAULT_JSON

    private fun decode(raw: String): List<AgentBackendConfig> =
        json.decodeFromString(ListSerializer(AgentBackendConfig.serializer()), raw)

    // ---- transport migration ------------------------------------------------

    @Test fun `an explicit transport is used as-is`() {
        HermesTransportPreference.values().forEach { choice ->
            val config = AgentBackendConfig(displayName = "H", type = BackendType.HERMES_API_SERVER, transport = choice)
            assertEquals(choice, config.effectiveTransport)
        }
    }

    @Test fun `an explicit transport wins over the legacy flag`() {
        val config = AgentBackendConfig(
            displayName = "H",
            type = BackendType.HERMES_API_SERVER,
            transport = HermesTransportPreference.SESSION_CHAT,
            useRunsApi = false,
        )
        assertEquals(HermesTransportPreference.SESSION_CHAT, config.effectiveTransport)
    }

    @Test fun `an install that left the runs default alone is moved to automatic`() {
        val config = AgentBackendConfig(
            displayName = "H",
            type = BackendType.HERMES_API_SERVER,
            transport = null,
            useRunsApi = true,
        )
        assertEquals(HermesTransportPreference.AUTO, config.effectiveTransport)
    }

    @Test fun `an install that turned runs off keeps chat completions`() {
        // That user opted out of the richer transport deliberately; upgrading
        // them to AUTO would change which endpoints their server sees.
        val config = AgentBackendConfig(
            displayName = "H",
            type = BackendType.HERMES_API_SERVER,
            transport = null,
            useRunsApi = false,
        )
        assertEquals(HermesTransportPreference.CHAT_COMPLETIONS, config.effectiveTransport)
    }

    // ---- persisted shape ----------------------------------------------------

    @Test fun `a config written before transports existed still loads`() {
        val legacy = """
            [{"id":"h1","displayName":"Hermes","type":"HERMES_API_SERVER","enabled":true,"isPrimary":true,
              "baseUrl":"http://h:8642","apiKeyOrToken":"k","modelName":"default","useRunsApi":true,
              "useStreaming":true,"createdAt":1,"updatedAt":2,"secondaryUrls":[]}]
        """.trimIndent()

        val config = decode(legacy).single()

        assertEquals("h1", config.id)
        assertNull(config.transport)
        assertEquals(HermesTransportPreference.AUTO, config.effectiveTransport)
        assertNull(config.providerName)
        assertNull(config.memoryScopeKey)
    }

    @Test fun `a config carrying fields a future build added still loads`() {
        val forward = """
            [{"id":"h1","displayName":"Hermes","type":"HERMES_API_SERVER","somethingNew":{"a":1}}]
        """.trimIndent()
        assertEquals("h1", decode(forward).single().id)
    }

    @Test fun `a round trip preserves every branch-relevant field`() {
        val original = AgentBackendConfig(
            id = "h1",
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            baseUrl = "http://h:8642",
            apiKeyOrToken = "k",
            modelName = "kimi-k2",
            providerName = "moonshot",
            transport = HermesTransportPreference.SESSION_CHAT,
            memoryScopeKey = "artur",
            secondaryUrls = listOf("http://lan", "http://vpn"),
        )

        val restored = decode(
            json.encodeToString(ListSerializer(AgentBackendConfig.serializer()), listOf(original)),
        ).single()

        assertEquals(original, restored)
    }

    @Test fun `an unknown backend type is rejected rather than silently mistyped`() {
        // Falling back to a default type would point a conversation at the wrong
        // product; the repository catches this and starts empty instead.
        val bad = """[{"id":"x","displayName":"X","type":"SOMETHING_ELSE"}]"""
        val repo = BackendRepository(InMemorySharedPreferences().apply {
            edit().putString("backends.v1", bad).apply()
        })
        assertTrue(repo.backends.value.isEmpty())
    }

    @Test fun `a gateway config carries no hermes-only fields by default`() {
        val gateway = AgentBackendConfig(
            displayName = "OpenClaw",
            type = BackendType.OPENCLAW_GATEWAY,
            host = "gw",
            port = 8443,
        )
        assertNull(gateway.providerName)
        assertNull(gateway.memoryScopeKey)
        assertNull(gateway.transport)
        assertTrue(gateway.secondaryUrls.isEmpty())
    }
}
