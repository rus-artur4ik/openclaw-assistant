package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end tests of the Hermes transports against a stubbed API server:
 * request shapes on the wire, SSE consumption, and the run-control calls that
 * unblock a waiting agent.
 */
class HermesStreamingTest {

    private lateinit var server: MockWebServer
    private val received = ConcurrentHashMap<String, String>()
    private val hits = ConcurrentHashMap<String, Int>()

    @Before fun setUp() {
        HermesCapabilityCache.clear()
        HermesEndpointSelection.clear()
        received.clear()
        hits.clear()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
        HermesCapabilityCache.clear()
        HermesEndpointSelection.clear()
    }

    private fun config(transport: HermesTransportPreference) = AgentBackendConfig(
        id = "hermes-streaming-test",
        displayName = "Hermes",
        type = BackendType.HERMES_API_SERVER,
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyOrToken = "key-123",
        transport = transport,
    )

    private fun sse(vararg frames: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { "$it\n\n" })

    private fun capabilitiesBody(vararg features: String) = """
        {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
         "auth":{"type":"bearer","required":true},
         "features":{${features.joinToString(",") { "\"$it\":true" }}}}
    """.trimIndent()

    /** Routes by path so tests do not depend on the client's request ordering. */
    private fun route(handler: (RecordedRequest) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                hits.merge(path, 1, Int::plus)
                request.body.readUtf8().takeIf { it.isNotBlank() }?.let { received[path] = it }
                return handler(request) ?: MockResponse().setResponseCode(404)
            }
        }
    }

    // ------------------------------------------------------------------
    // Runs transport
    // ------------------------------------------------------------------

    @Test fun `a runs turn sends history and streams tokens to completion`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("run_submission", "run_events_sse", "run_stop", "approval_events"))
                "/v1/runs" -> MockResponse().setResponseCode(200)
                    .setBody("""{"run_id":"run_abc","status":"started"}""")
                "/v1/runs/run_abc/events" -> sse(
                    """data: {"event":"tool.started","run_id":"run_abc","tool":"web.search","preview":"q=weather"}""",
                    """data: {"event":"message.delta","run_id":"run_abc","delta":"It is "}""",
                    """data: {"event":"message.delta","run_id":"run_abc","delta":"sunny."}""",
                    """data: {"event":"run.completed","run_id":"run_abc","output":"It is sunny."}""",
                )
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.RUNS)).sendMessage(
            listOf(
                AgentMessage.user("what was the weather yesterday"),
                AgentMessage.assistant("It rained."),
                AgentMessage.user("and today"),
            ),
            AgentSendOptions(sessionId = "session-1"),
        ).toList()

        val body = received["/v1/runs"].orEmpty()
        assertTrue("history must travel as conversation_history", body.contains("\"conversation_history\""))
        assertTrue(body.contains("\"input\":\"and today\""))
        assertTrue(body.contains("\"session_id\":\"session-1\""))

        assertTrue(events.any { it is AgentEvent.ToolProgress && it.tool == "web.search" })
        assertEquals(
            "It is sunny.",
            events.filterIsInstance<AgentEvent.TokenDelta>().joinToString("") { it.text },
        )
        assertEquals("It is sunny.", events.filterIsInstance<AgentEvent.Completed>().single().finalText)
    }

    @Test fun `the memory scope header is sent when configured`() = runBlocking {
        var header: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path == "/v1/runs") header = request.getHeader("X-Hermes-Session-Key")
                return when (path) {
                    "/v1/capabilities" -> MockResponse().setResponseCode(200)
                        .setBody(capabilitiesBody("run_submission", "run_events_sse"))
                    "/v1/runs" -> MockResponse().setResponseCode(200).setBody("""{"run_id":"r1"}""")
                    "/v1/runs/r1/events" -> sse("""data: {"event":"run.completed","run_id":"r1","output":"ok"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        HermesApiServerClient(
            config(HermesTransportPreference.RUNS).copy(memoryScopeKey = "agent:main:mobile:user-42"),
        ).sendMessage(listOf(AgentMessage.user("hi")), AgentSendOptions(sessionId = "s1")).toList()

        assertEquals("agent:main:mobile:user-42", header)
    }

    // ------------------------------------------------------------------
    // Approvals
    // ------------------------------------------------------------------

    @Test fun `a headless turn declines a tool approval instead of hanging`() = runBlocking {
        // The agent blocks until the approval is answered. Leaving it unanswered
        // wedges the run for the server's five-minute timeout.
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("run_submission", "run_events_sse", "run_approval_response", "approval_events"))
                "/v1/runs" -> MockResponse().setResponseCode(200).setBody("""{"run_id":"r1"}""")
                "/v1/runs/r1/events" -> sse(
                    """data: {"event":"approval.request","run_id":"r1","request_id":"a1","command":"rm -rf /","choices":["once","deny"]}""",
                    """data: {"event":"run.completed","run_id":"r1","output":"declined"}""",
                )
                "/v1/runs/r1/approval" -> MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.RUNS)).sendMessage(
            listOf(AgentMessage.user("clean up")),
            AgentSendOptions(sessionId = "s1", approvalPolicy = ApprovalPolicy.DENY),
        ).toList()

        val approval = events.filterIsInstance<AgentEvent.ApprovalRequest>().single()
        assertEquals("rm -rf /", approval.command)
        assertEquals(1, hits["/v1/runs/r1/approval"])
        assertTrue(received["/v1/runs/r1/approval"].orEmpty().contains("\"choice\":\"deny\""))
    }

    @Test fun `an interactive turn leaves the approval for the user to answer`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("run_submission", "run_events_sse", "run_approval_response"))
                "/v1/runs" -> MockResponse().setResponseCode(200).setBody("""{"run_id":"r1"}""")
                "/v1/runs/r1/events" -> sse(
                    """data: {"event":"approval.request","run_id":"r1","choices":["once","session","deny"]}""",
                    """data: {"event":"run.completed","run_id":"r1","output":"done"}""",
                )
                "/v1/runs/r1/approval" -> MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.RUNS)).sendMessage(
            listOf(AgentMessage.user("go")),
            AgentSendOptions(sessionId = "s1", approvalPolicy = ApprovalPolicy.ASK),
        ).toList()

        assertNotNull(events.filterIsInstance<AgentEvent.ApprovalRequest>().singleOrNull())
        assertEquals(null, hits["/v1/runs/r1/approval"])
    }

    @Test fun `an approval can be answered by any client instance, not just the one streaming`() = runBlocking {
        // Clients are rebuilt per call site, so the Stop and Approve buttons must
        // work from a fresh instance.
        route { MockResponse().setResponseCode(200).setBody("""{"ok":true}""") }
        val answered = HermesApiServerClient(config(HermesTransportPreference.RUNS))
            .respondToApproval("r1", "once")
        assertTrue(answered)
        assertTrue(received["/v1/runs/r1/approval"].orEmpty().contains("\"choice\":\"once\""))
    }

    // ------------------------------------------------------------------
    // Session-chat transport
    // ------------------------------------------------------------------

    @Test fun `a session turn creates the session and consumes named events`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("session_chat", "session_chat_streaming", "session_resources"))
                "/api/sessions" -> MockResponse().setResponseCode(201)
                    .setBody("""{"object":"hermes.session","session":{"id":"s1"}}""")
                "/api/sessions/s1/chat/stream" -> sse(
                    """event: run.started
data: {"session_id":"s1","run_id":"r9","seq":1}""",
                    """event: assistant.delta
data: {"session_id":"s1","run_id":"r9","message_id":"m1","delta":"Hello"}""",
                    """event: assistant.completed
data: {"session_id":"s1","message_id":"m1","content":"Hello there","interrupted":false}""",
                    """event: run.completed
data: {"session_id":"s1","run_id":"r9","completed":true}""",
                    """event: done
data: {"session_id":"s1","run_id":"r9"}""",
                )
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.SESSION_CHAT)).sendMessage(
            listOf(AgentMessage.user("older"), AgentMessage.assistant("ok"), AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        // The server owns the transcript, so only the new turn goes on the wire.
        val chatBody = received["/api/sessions/s1/chat/stream"].orEmpty()
        assertTrue(chatBody.contains("\"message\":\"hi\""))
        assertTrue("history must not be re-uploaded", !chatBody.contains("older"))

        assertEquals("Hello there", events.filterIsInstance<AgentEvent.Completed>().last().finalText)
    }

    @Test fun `an existing session is reused rather than treated as an error`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("session_chat", "session_chat_streaming"))
                // The server 409s when the id already exists — the normal case
                // for every turn after the first.
                "/api/sessions" -> MockResponse().setResponseCode(409)
                    .setBody("""{"error":{"message":"Session already exists: s1"}}""")
                "/api/sessions/s1/chat/stream" -> sse(
                    """event: run.completed
data: {"session_id":"s1","run_id":"r1","completed":true}""",
                )
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.SESSION_CHAT)).sendMessage(
            listOf(AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        assertTrue(events.none { it is AgentEvent.Error })
        assertEquals(1, hits["/api/sessions/s1/chat/stream"])
    }

    // ------------------------------------------------------------------
    // Failure reporting
    // ------------------------------------------------------------------

    @Test fun `the concurrency cap is explained rather than shown as a bare 429`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(200)
                    .setBody(capabilitiesBody("run_submission", "run_events_sse"))
                "/v1/runs" -> MockResponse().setResponseCode(429)
                    .setHeader("Retry-After", "2")
                    .setBody("""{"error":{"message":"Too many concurrent runs (max 10)"}}""")
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.RUNS)).sendMessage(
            listOf(AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        val error = events.filterIsInstance<AgentEvent.Error>().single().message
        assertTrue(error, error.contains("concurrent-run limit"))
        assertTrue(error, error.contains("2"))
    }

    @Test fun `a rejected key is named as such`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(401)
                "/v1/chat/completions" -> MockResponse().setResponseCode(401)
                    .setBody("""{"error":{"message":"Invalid API key"}}""")
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.CHAT_COMPLETIONS)).sendMessage(
            listOf(AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        val error = events.filterIsInstance<AgentEvent.Error>().single().message
        assertTrue(error, error.contains("rejected the API key"))
        assertTrue(error, error.contains("Invalid API key"))
    }

    @Test fun `a server without capabilities still works over chat completions`() = runBlocking {
        route { request ->
            when (request.path.orEmpty().substringBefore('?')) {
                "/v1/capabilities" -> MockResponse().setResponseCode(404)
                "/v1/chat/completions" -> sse(
                    """data: {"choices":[{"delta":{"content":"hi"}}]}""",
                    "data: [DONE]",
                )
                else -> null
            }
        }

        val events = HermesApiServerClient(config(HermesTransportPreference.AUTO)).sendMessage(
            listOf(AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        assertEquals("hi", events.filterIsInstance<AgentEvent.Completed>().single().finalText)
    }

    // ------------------------------------------------------------------
    // Run control
    // ------------------------------------------------------------------

    @Test fun `stopping a run posts to the server, not just cancelling the socket`() = runBlocking {
        route { MockResponse().setResponseCode(200).setBody("""{"status":"stopping"}""") }
        assertTrue(HermesApiServerClient(config(HermesTransportPreference.RUNS)).stopRun("r1"))
        assertEquals(1, hits["/v1/runs/r1/stop"])
    }

    @Test fun `steering an in-flight run sends the new instruction`() = runBlocking {
        route { MockResponse().setResponseCode(200).setBody("""{"status":"steered"}""") }
        assertTrue(
            HermesApiServerClient(config(HermesTransportPreference.RUNS))
                .steerRun("r1", "use the other file"),
        )
        assertTrue(received["/v1/runs/r1/steer"].orEmpty().contains("use the other file"))
    }
}
