package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OpenClaw side of the [AgentClient] contract.
 *
 * The Hermes client has had this covered from the start; these two adapters are
 * what the backend list, the connection test and the HTTP send path go through,
 * and they had nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OpenClawAdaptersTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun httpConfig(baseUrl: String?, model: String? = null, token: String? = null) = AgentBackendConfig(
        id = "api",
        displayName = "OpenClaw API",
        type = BackendType.OPENCLAW_HTTP,
        baseUrl = baseUrl,
        modelName = model,
        apiKeyOrToken = token,
    )

    private fun gatewayConfig(host: String?, port: Int?) = AgentBackendConfig(
        id = "gw",
        displayName = "OpenClaw",
        type = BackendType.OPENCLAW_GATEWAY,
        host = host,
        port = port,
    )

    // ---- factory ------------------------------------------------------------

    @Test fun `each backend type builds its own client`() {
        assertTrue(AgentClientFactory.create(httpConfig("http://x")) is OpenClawHttpAdapter)
        assertTrue(AgentClientFactory.create(gatewayConfig("h", 1)) is OpenClawGatewayAdapter)
        assertTrue(
            AgentClientFactory.create(
                AgentBackendConfig(displayName = "H", type = BackendType.HERMES_API_SERVER, baseUrl = "http://h"),
            ) is HermesApiServerClient,
        )
    }

    // ---- http adapter -------------------------------------------------------

    @Test fun `the http adapter completes with the server answer`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"pong"}}]}"""))
        val adapter = OpenClawHttpAdapter(httpConfig(server.url("/v1/chat/completions").toString()))

        val events = adapter.sendMessage(listOf(AgentMessage.user("ping")), AgentSendOptions()).toList()

        assertTrue(events.first() is AgentEvent.Started)
        assertEquals("pong", (events.last() as AgentEvent.Completed).finalText)
    }

    @Test fun `the http adapter sends the last user turn`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val adapter = OpenClawHttpAdapter(httpConfig(server.url("/v1/chat/completions").toString()))

        adapter.sendMessage(
            listOf(AgentMessage.user("older"), AgentMessage.assistant("reply"), AgentMessage.user("newest")),
            AgentSendOptions(),
        ).toList()

        assertTrue(server.takeRequest().body.readUtf8().contains("newest"))
    }

    @Test fun `the http adapter errors instead of dialling a blank url`() = runBlocking {
        val events = OpenClawHttpAdapter(httpConfig(null))
            .sendMessage(listOf(AgentMessage.user("hi")), AgentSendOptions()).toList()

        assertEquals(1, events.size)
        assertTrue((events.single() as AgentEvent.Error).message.contains("baseUrl"))
    }

    @Test fun `the http adapter errors on an empty message`() = runBlocking {
        val events = OpenClawHttpAdapter(httpConfig(server.url("/").toString()))
            .sendMessage(listOf(AgentMessage.assistant("only an assistant turn")), AgentSendOptions()).toList()

        assertTrue((events.single() as AgentEvent.Error).message.contains("empty"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `the http adapter reports a server failure as an error event`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val events = OpenClawHttpAdapter(httpConfig(server.url("/v1/chat/completions").toString()))
            .sendMessage(listOf(AgentMessage.user("hi")), AgentSendOptions()).toList()

        assertTrue(events.any { it is AgentEvent.Error })
    }

    @Test fun `the http connection test accepts a reachable server`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = OpenClawHttpAdapter(httpConfig(server.url("/").toString())).testConnection()
        assertTrue(result.ok)
    }

    @Test fun `the http connection test rejects a url that is not http`() = runBlocking {
        val result = OpenClawHttpAdapter(httpConfig("gateway.local:8443")).testConnection()
        assertFalse(result.ok)
        assertEquals("Invalid baseUrl", result.message)
    }

    // ---- gateway adapter ----------------------------------------------------

    @Test fun `the gateway connection test accepts a host and port`() = runBlocking {
        val result = OpenClawGatewayAdapter(gatewayConfig("gw.local", 8443)).testConnection()
        assertTrue(result.ok)
        assertTrue(result.message.contains("gw.local:8443"))
    }

    @Test fun `the gateway connection test rejects a missing port`() = runBlocking {
        assertFalse(OpenClawGatewayAdapter(gatewayConfig("gw.local", null)).testConnection().ok)
    }

    @Test fun `the gateway connection test rejects an out-of-range port`() = runBlocking {
        assertFalse(OpenClawGatewayAdapter(gatewayConfig("gw.local", 70_000)).testConnection().ok)
    }

    @Test fun `the gateway adapter refuses to send outside the dispatcher`() = runBlocking {
        // It has no NodeRuntime, so a send here would silently do nothing;
        // saying so is what keeps the caller on PrimaryBackendDispatcher.
        val events = OpenClawGatewayAdapter(gatewayConfig("gw.local", 8443))
            .sendMessage(listOf(AgentMessage.user("hi")), AgentSendOptions()).toList()

        assertTrue((events.single() as AgentEvent.Error).message.contains("PrimaryBackendDispatcher"))
    }

    // ---- run control defaults ----------------------------------------------

    @Test fun `openclaw clients tolerate run control calls they cannot serve`() = runBlocking {
        // Chat offers Stop and approvals generically; these must be no-ops on a
        // backend without runs rather than crashing the turn.
        val http = OpenClawHttpAdapter(httpConfig(server.url("/").toString()))
        val gateway = OpenClawGatewayAdapter(gatewayConfig("gw.local", 8443))
        listOf(http, gateway).forEach { client ->
            client.stopRun("run-1")
            client.respondToApproval("run-1", "deny")
            client.steerRun("run-1", "stop that")
        }
        assertEquals(0, server.requestCount)
    }
}
