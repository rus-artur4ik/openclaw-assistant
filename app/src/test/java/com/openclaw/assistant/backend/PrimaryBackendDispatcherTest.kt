package com.openclaw.assistant.backend

import androidx.test.core.app.ApplicationProvider
import com.openclaw.assistant.ui.BackendTestEnv
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The single junction every non-gateway entry point goes through — Chat, the
 * wake word, the Voice Overlay and the system assistant all land here.
 *
 * These run a real Hermes server on MockWebServer so the whole path is covered:
 * target selection, capability probe, transport choice, streaming, approvals
 * and the events the caller renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PrimaryBackendDispatcherTest {

    @get:Rule val backends = BackendTestEnv()

    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        HermesCapabilityCache.clear()
        HermesEndpointSelection.clear()
    }

    @After fun tearDown() {
        server.shutdown()
        HermesCapabilityCache.clear()
        HermesEndpointSelection.clear()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun hermesBackend(id: String = "hermes-1", primary: Boolean = true): AgentBackendConfig {
        val config = AgentBackendConfig(
            id = id,
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            isPrimary = primary,
            baseUrl = baseUrl(),
            apiKeyOrToken = "test-key",
            transport = HermesTransportPreference.CHAT_COMPLETIONS,
            useStreaming = false,
        )
        backends.repo.upsert(config)
        if (primary) backends.repo.setPrimary(id)
        return backends.repo.backends.value.first { it.id == id }
    }

    /** A Hermes server that answers the probe and one non-streaming completion. */
    private fun serveChatCompletion(answer: String, capture: MutableList<RecordedRequest> = mutableListOf()) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                capture += request
                return when {
                    request.path?.startsWith("/v1/capabilities") == true ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"model":"hermes-agent","features":{"chat_completions":true}}""")
                    request.path?.startsWith("/v1/models") == true ->
                        MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"hermes-agent"}]}""")
                    request.path?.startsWith("/v1/chat/completions") == true ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"choices":[{"message":{"role":"assistant","content":"$answer"}}]}""",
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    // ---- target selection ---------------------------------------------------

    @Test fun `no configured backend yields null rather than an error`() = runBlocking {
        // Legacy installs fall back to the original settings pipeline, which only
        // works if the dispatcher declines instead of throwing.
        assertNull(PrimaryBackendDispatcher.send(context, "hello"))
    }

    @Test fun `disabling the last backend cannot leave the app with no target`() {
        // The repository keeps exactly one enabled primary while any backend
        // exists, so switching off the only one is a no-op rather than a state
        // where every entry point silently stops working.
        val hermes = hermesBackend()
        backends.repo.setEnabled(hermes.id, false)

        val reloaded = backends.repo.backends.value.single()
        assertTrue(reloaded.enabled)
        assertTrue(reloaded.isPrimary)
    }

    @Test fun `disabling the primary hands the branch over to the other product`() {
        hermesBackend(id = "hermes-1", primary = true)
        backends.gateway(id = "gateway-1")

        backends.repo.setEnabled("hermes-1", false)

        assertEquals(BackendType.OPENCLAW_GATEWAY, backends.repo.primary?.type)
    }

    @Test fun `an unknown explicit backend id yields null`() = runBlocking {
        hermesBackend()
        assertNull(PrimaryBackendDispatcher.send(context, "hello", backendId = "nope"))
    }

    @Test fun `the primary hermes backend answers`() = runBlocking {
        hermesBackend()
        serveChatCompletion("hi from hermes")

        val reply = PrimaryBackendDispatcher.send(context, "hello")

        assertNotNull(reply)
        assertEquals("hi from hermes", reply!!.text)
        assertEquals("Hermes", reply.sourceDisplayName)
    }

    @Test fun `an explicit backend id overrides the primary`() = runBlocking {
        hermesBackend(id = "primary-hermes", primary = true)
        val other = AgentBackendConfig(
            id = "other-hermes",
            displayName = "Other Hermes",
            type = BackendType.HERMES_API_SERVER,
            baseUrl = baseUrl(),
            apiKeyOrToken = "test-key",
            transport = HermesTransportPreference.CHAT_COMPLETIONS,
            useStreaming = false,
        )
        backends.repo.upsert(other)
        serveChatCompletion("from the other one")

        val reply = PrimaryBackendDispatcher.send(context, "hello", backendId = "other-hermes")

        assertEquals("Other Hermes", reply?.sourceDisplayName)
    }

    // ---- what reaches the server -------------------------------------------

    @Test fun `the conversation history is forwarded`() = runBlocking {
        hermesBackend()
        val seen = mutableListOf<RecordedRequest>()
        serveChatCompletion("ok", seen)

        PrimaryBackendDispatcher.send(
            context,
            "and the second one?",
            history = listOf(AgentMessage.user("first question"), AgentMessage.assistant("first answer")),
        )

        val body = seen.first { it.path?.startsWith("/v1/chat/completions") == true }.body.readUtf8()
        assertTrue("history missing from $body", body.contains("first question"))
        assertTrue("history missing from $body", body.contains("first answer"))
        assertTrue(body.contains("and the second one?"))
    }

    @Test fun `the bearer token is sent`() = runBlocking {
        hermesBackend()
        val seen = mutableListOf<RecordedRequest>()
        serveChatCompletion("ok", seen)

        PrimaryBackendDispatcher.send(context, "hello")

        val chat = seen.first { it.path?.startsWith("/v1/chat/completions") == true }
        assertEquals("Bearer test-key", chat.getHeader("Authorization"))
    }

    // ---- streaming events ---------------------------------------------------

    @Test fun `streamed deltas reach the caller and are assembled`() = runBlocking {
        val config = AgentBackendConfig(
            id = "streaming-hermes",
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            isPrimary = true,
            baseUrl = baseUrl(),
            apiKeyOrToken = "k",
            transport = HermesTransportPreference.CHAT_COMPLETIONS,
            useStreaming = true,
        )
        backends.repo.upsert(config)
        backends.repo.setPrimary(config.id)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/v1/capabilities") == true ->
                    MockResponse().setResponseCode(200)
                        .setBody("""{"model":"hermes-agent","features":{"chat_completions":true,"chat_completions_streaming":true}}""")
                request.path?.startsWith("/v1/chat/completions") == true ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                                "data: [DONE]\n\n",
                        )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val events = mutableListOf<AgentEvent>()
        val reply = PrimaryBackendDispatcher.send(context, "hi", onEvent = { events += it })

        assertEquals("Hello", reply?.text)
        assertTrue(events.any { it is AgentEvent.TokenDelta || it is AgentEvent.MessageDelta })
    }

    // ---- failures -----------------------------------------------------------

    @Test fun `an auth failure surfaces a readable error`() {
        hermesBackend()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/v1/capabilities") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"features":{"chat_completions":true}}""")
                else -> MockResponse().setResponseCode(401).setBody("unauthorized")
            }
        }

        val error = runCatching { runBlocking { PrimaryBackendDispatcher.send(context, "hello") } }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(
            "unhelpful message: ${error?.message}",
            error?.message.orEmpty().contains("key", ignoreCase = true) ||
                error?.message.orEmpty().contains("401") ||
                error?.message.orEmpty().contains("auth", ignoreCase = true),
        )
    }

    @Test fun `an empty answer is reported rather than passed on as silence`() {
        hermesBackend()
        serveChatCompletion("")

        val error = runCatching { runBlocking { PrimaryBackendDispatcher.send(context, "hello") } }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("empty", ignoreCase = true))
    }
}
