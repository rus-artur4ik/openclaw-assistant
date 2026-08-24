package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesCapabilitiesTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        HermesCapabilityCache.clear()
    }

    @After fun tearDown() {
        server.shutdown()
        HermesCapabilityCache.clear()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test fun `the advertised feature map is parsed`() {
        val root = Json.parseToJsonElement(
            """
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"my-hermes",
             "auth":{"type":"bearer","required":true},
             "features":{"chat_completions":true,"run_submission":true,"run_events_sse":true,
                         "run_stop":true,"run_steer":true,"run_approval_response":true,
                         "session_chat_streaming":true,"session_model_lock":true,
                         "admin_config_rw":false,
                         "session_continuity_header":"X-Hermes-Session-Id",
                         "session_key_header":"X-Hermes-Session-Key"}}
            """.trimIndent(),
        ).jsonObject

        val caps = HermesCapabilitiesProbe().parse(root)

        assertEquals("my-hermes", caps.advertisedModel)
        assertTrue(caps.runSteer)
        assertTrue(caps.runApprovalResponse)
        assertTrue(caps.sessionModelLock)
        assertEquals("X-Hermes-Session-Id", caps.sessionContinuityHeader)
        assertTrue(caps.detected)
        // Flags the server did not advertise stay off rather than being assumed.
        assertFalse(caps.skillsApi)
    }

    @Test fun `session chat wins when the server offers everything`() {
        val caps = HermesCapabilities(
            runSubmission = true,
            runEventsSse = true,
            sessionChatStreaming = true,
            detected = true,
        )
        assertEquals(HermesTransport.SESSION_CHAT, caps.preferredTransport())
        assertEquals(HermesTransport.RUNS, caps.preferredTransport(allowSessions = false))
    }

    @Test fun `an undetected probe claims nothing beyond chat completions`() {
        assertEquals(HermesTransport.CHAT_COMPLETIONS, HermesCapabilities.LEGACY.preferredTransport())
        assertFalse(HermesCapabilities.LEGACY.detected)
    }

    @Test fun `a successful probe is cached instead of re-fetched`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"model":"hermes-agent","features":{"run_submission":true}}"""),
        )

        val probe = HermesCapabilitiesProbe()
        val first = HermesCapabilityCache.get("b1", baseUrl(), null, probe)
        val second = HermesCapabilityCache.get("b1", baseUrl(), null, probe)

        assertTrue(first.detected)
        assertEquals(first, second)
        assertEquals("only the first call should hit the server", 1, server.requestCount)
    }

    @Test fun `a failed probe is retried rather than downgrading the app for good`() = runBlocking {
        // A timeout during a network handoff must not permanently strip tool
        // approvals, tool progress and the Stop button.
        server.enqueue(MockResponse().setResponseCode(503))
        val probe = HermesCapabilitiesProbe()

        val degraded = HermesCapabilityCache.get("b2", baseUrl(), null, probe)
        assertFalse(degraded.detected)

        // Within the retry window the negative result is reused, so a burst of
        // sends does not re-probe on every turn...
        HermesCapabilityCache.get("b2", baseUrl(), null, probe)
        assertEquals(1, server.requestCount)

        // ...but it is not sticky: dropping the entry the way the retry window
        // does lets the next send discover the real capabilities.
        HermesCapabilityCache.invalidate("b2")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"model":"hermes-agent","features":{"run_submission":true,"run_events_sse":true}}"""),
        )
        val recovered = HermesCapabilityCache.get("b2", baseUrl(), null, probe)
        assertTrue(recovered.detected)
        assertEquals(HermesTransport.RUNS, recovered.preferredTransport())
    }

    @Test fun `a malformed capabilities payload degrades instead of throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        val caps = HermesCapabilitiesProbe().fetch(baseUrl(), null)
        assertFalse(caps.detected)
    }
}
