package com.openclaw.assistant.api

import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OpenClaw HTTP wire, tested the way [com.openclaw.assistant.backend.HermesApiServerClient]
 * already is.
 *
 * This is the branch that gets hand-tested least, because most people run
 * either the gateway or Hermes. Nothing here had coverage before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OpenClawClientTest {

    private lateinit var server: MockWebServer
    private val client = OpenClawClient()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        // The client reports non-transient failures to Crashlytics, which is not
        // initialised on the JVM.
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)
    }

    @After fun tearDown() {
        server.shutdown()
        unmockkStatic(FirebaseCrashlytics::class)
    }

    private fun url() = server.url("/v1/chat/completions").toString()

    private fun send(
        message: String = "hello",
        sessionId: String = "s1",
        authToken: String? = null,
        agentId: String? = null,
        modelName: String? = null,
        attachments: List<Pair<String, String>> = emptyList(),
    ) = runBlocking {
        client.sendMessage(url(), message, sessionId, authToken, agentId, modelName, attachments)
    }

    // ---- request shape ------------------------------------------------------

    @Test fun `a text turn is sent in OpenAI chat format`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))

        send(message = "what time is it", sessionId = "session-7")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("openclaw", body.getString("model"))
        assertEquals("session-7", body.getString("user"))
        val first = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("what time is it", first.getString("content"))
    }

    @Test fun `a configured model overrides the default`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        send(modelName = "gpt-5")
        assertEquals("gpt-5", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
    }

    @Test fun `a blank model falls back to the default`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        send(modelName = "   ")
        assertEquals("openclaw", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
    }

    @Test fun `an auth token is sent as a bearer header`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        send(authToken = "  tok  ")
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `no token means no authorization header`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        send(authToken = null)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `an agent id travels in its own header`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        send(agentId = "research")
        assertEquals("research", server.takeRequest().getHeader("x-openclaw-agent-id"))
    }

    @Test fun `an image turn uses the vision content array`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"a cat"}}]}"""))

        send(message = "what is this", attachments = listOf("image/png" to "QUJD"))

        val content = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("what is this", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test fun `an image with no caption omits the text part`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"a cat"}}]}"""))

        send(message = "", attachments = listOf("image/jpeg" to "QUJD"))

        val content = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("image_url", content.getJSONObject(0).getString("type"))
    }

    // ---- response parsing ---------------------------------------------------

    @Test fun `the OpenAI shape is read`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"the answer"}}]}"""))
        assertEquals("the answer", send().getOrNull()?.getResponseText())
    }

    @Test fun `the response field is read as a fallback`() {
        server.enqueue(MockResponse().setBody("""{"response":"the answer"}"""))
        assertEquals("the answer", send().getOrNull()?.getResponseText())
    }

    @Test fun `the text field is read as a fallback`() {
        server.enqueue(MockResponse().setBody("""{"text":"the answer"}"""))
        assertEquals("the answer", send().getOrNull()?.getResponseText())
    }

    @Test fun `the message field is read as a fallback`() {
        server.enqueue(MockResponse().setBody("""{"message":"the answer"}"""))
        assertEquals("the answer", send().getOrNull()?.getResponseText())
    }

    @Test fun `an unrecognised body is surfaced verbatim rather than dropped`() {
        server.enqueue(MockResponse().setBody("just a plain string"))
        assertEquals("just a plain string", send().getOrNull()?.getResponseText())
    }

    // ---- failures -----------------------------------------------------------

    @Test fun `an http error becomes a failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val error = send().exceptionOrNull()
        assertTrue("was ${error?.message}", error?.message.orEmpty().contains("500"))
    }

    @Test fun `an empty body becomes a failure`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertTrue(send().exceptionOrNull()?.message.orEmpty().contains("Empty", ignoreCase = true))
    }

    @Test fun `a structured API error is surfaced with its message`() {
        server.enqueue(MockResponse().setBody("""{"error":{"message":"model not found"}}"""))
        val error = send().exceptionOrNull()
        assertTrue("was ${error?.message}", error?.message.orEmpty().contains("model not found"))
    }

    @Test fun `an unconfigured url fails without a request`() {
        val result = runBlocking { client.sendMessage("", "hi", "s1") }
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a malformed url fails without a request`() {
        val result = runBlocking { client.sendMessage("not a url", "hi", "s1") }
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    // ---- connection test ----------------------------------------------------

    @Test fun `a successful HEAD is enough to pass the connection test`() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(true, runBlocking { client.testConnection(url(), null) }.getOrNull())
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test fun `a server that rejects HEAD is retried with POST`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"pong"}}]}"""))

        assertEquals(true, runBlocking { client.testConnection(url(), null) }.getOrNull())
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun `an unauthorised connection test fails`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runBlocking { client.testConnection(url(), "bad") }.exceptionOrNull()
        assertTrue("was ${error?.message}", error?.message.orEmpty().contains("401"))
    }
}
