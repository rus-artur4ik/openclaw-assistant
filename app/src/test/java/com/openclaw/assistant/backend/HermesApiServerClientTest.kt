package com.openclaw.assistant.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests against the real Hermes Agent API server
 * (NousResearch/hermes-agent, `gateway/platforms/api_server.py`).
 *
 * The request-shape tests exist because the server silently ignores keys it
 * does not know: sending history under the wrong name produced a request that
 * looked right in a log and lost the conversation.
 */
class HermesApiServerClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun client(
        model: String? = null,
        provider: String? = null,
        transport: HermesTransportPreference = HermesTransportPreference.AUTO,
    ) = HermesApiServerClient(
        AgentBackendConfig(
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            baseUrl = "http://host:8642",
            apiKeyOrToken = "key-123",
            modelName = model,
            providerName = provider,
            transport = transport,
        ),
    )

    private fun body(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    // ------------------------------------------------------------------
    // Runs request body
    // ------------------------------------------------------------------

    @Test fun `runs body sends history as conversation_history, not messages`() {
        val request = body(
            client().buildRunsRequestBody(
                listOf(
                    AgentMessage.user("first"),
                    AgentMessage.assistant("ok"),
                    AgentMessage.user("latest"),
                ),
                AgentSendOptions(),
                HermesCapabilities.LEGACY,
            ),
        )

        // `messages` is not a field /v1/runs reads; sending it loses the history.
        assertFalse("must not send a 'messages' key", request.containsKey("messages"))
        assertEquals("latest", request.str("input"))
        val history = request["conversation_history"]!!.jsonArray
        assertEquals(2, history.size)
        assertEquals("first", history[0].jsonObject.str("content"))
        assertEquals("assistant", history[1].jsonObject.str("role"))
    }

    @Test fun `runs body threads the session id and system instructions`() {
        val request = body(
            client().buildRunsRequestBody(
                listOf(AgentMessage.system("be concise"), AgentMessage.user("hi")),
                AgentSendOptions(sessionId = "session-7"),
                HermesCapabilities.LEGACY,
            ),
        )
        assertEquals("session-7", request.str("session_id"))
        assertEquals("be concise", request.str("instructions"))
        // A system turn becomes `instructions`; it must not also be replayed as history.
        assertNull(request["conversation_history"])
    }

    @Test fun `runs body omits history for a first turn`() {
        val request = body(
            client().buildRunsRequestBody(
                listOf(AgentMessage.user("hi")),
                AgentSendOptions(),
                HermesCapabilities.LEGACY,
            ),
        )
        assertEquals("hi", request.str("input"))
        assertNull(request["conversation_history"])
    }

    // ------------------------------------------------------------------
    // Model selection
    // ------------------------------------------------------------------

    @Test fun `placeholder model name is not sent to the provider`() {
        // Hermes forwards any model id straight through, so "default" would be
        // looked up as a real model and fail.
        val request = body(
            client(model = "default").buildRunsRequestBody(
                listOf(AgentMessage.user("hi")),
                AgentSendOptions(),
                HermesCapabilities.LEGACY,
            ),
        )
        assertNull(request["model"])
    }

    @Test fun `the server's own advertised alias is treated as use-the-default`() {
        val caps = HermesCapabilities.LEGACY.copy(advertisedModel = "hermes-agent")
        val request = body(
            client(model = "hermes-agent").buildRunsRequestBody(
                listOf(AgentMessage.user("hi")),
                AgentSendOptions(),
                caps,
            ),
        )
        assertNull(request["model"])
    }

    @Test fun `a real model is sent with its provider so the choice is honoured`() {
        // Without `provider`, chat completions discards the model unless the
        // operator opted in to direct model requests.
        val request = body(
            client(model = "anthropic/claude-sonnet-4.6", provider = "openrouter")
                .buildChatRequestBody(listOf(AgentMessage.user("hi")), stream = true),
        )
        assertEquals("anthropic/claude-sonnet-4.6", request.str("model"))
        assertEquals("openrouter", request.str("provider"))
    }

    @Test fun `per-request overrides beat the stored backend model`() {
        val request = body(
            client(model = "stored", provider = "stored-provider").buildRunsRequestBody(
                listOf(AgentMessage.user("hi")),
                AgentSendOptions(
                    modelOverride = "picked",
                    providerOverride = "picked-provider",
                    modelOptions = mapOf("reasoning_effort" to "high"),
                ),
                HermesCapabilities.LEGACY,
            ),
        )
        assertEquals("picked", request.str("model"))
        assertEquals("picked-provider", request.str("provider"))
        assertEquals("high", request["model_options"]!!.jsonObject.str("reasoning_effort"))
    }

    // ------------------------------------------------------------------
    // Multimodal + session chat
    // ------------------------------------------------------------------

    @Test fun `an attachment becomes an OpenAI image content part`() {
        val request = body(
            client().buildChatRequestBody(
                listOf(AgentMessage.user("what is this", listOf(AgentAttachment("image/png", "AAAA")))),
                stream = false,
            ),
        )
        val content = request["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals("text", content[0].jsonObject.str("type"))
        assertEquals("image_url", content[1].jsonObject.str("type"))
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]!!.jsonObject.str("url"),
        )
    }

    @Test fun `a text-only message stays a plain string`() {
        val request = body(client().buildChatRequestBody(listOf(AgentMessage.user("hi")), stream = false))
        assertEquals("hi", request["messages"]!!.jsonArray[0].jsonObject.str("content"))
    }

    @Test fun `session chat sends only the new turn, since the server keeps the rest`() {
        val request = body(
            client().buildSessionChatBody(
                listOf(
                    AgentMessage.system("be brief"),
                    AgentMessage.user("older"),
                    AgentMessage.assistant("ok"),
                    AgentMessage.user("newest"),
                ),
                AgentSendOptions(sessionId = "s1"),
                HermesCapabilities.LEGACY,
            ),
        )
        assertEquals("newest", request.str("message"))
        assertEquals("be brief", request.str("system_message"))
        assertNull(request["conversation_history"])
    }

    // ------------------------------------------------------------------
    // Transport selection
    // ------------------------------------------------------------------

    @Test fun `auto prefers session chat when the server offers it`() {
        val caps = HermesCapabilities.LEGACY.copy(
            sessionChatStreaming = true,
            runSubmission = true,
            runEventsSse = true,
            detected = true,
        )
        assertEquals(
            HermesTransport.SESSION_CHAT,
            client().chooseTransport(caps, listOf(AgentMessage.user("hi")), AgentSendOptions(sessionId = "s1")),
        )
    }

    @Test fun `auto falls back to runs without a session id to address`() {
        val caps = HermesCapabilities.LEGACY.copy(
            sessionChatStreaming = true,
            runSubmission = true,
            runEventsSse = true,
            detected = true,
        )
        assertEquals(
            HermesTransport.RUNS,
            client().chooseTransport(caps, listOf(AgentMessage.user("hi")), AgentSendOptions(sessionId = null)),
        )
    }

    @Test fun `auto falls back to chat completions on a server with neither`() {
        assertEquals(
            HermesTransport.CHAT_COMPLETIONS,
            client().chooseTransport(
                HermesCapabilities.LEGACY,
                listOf(AgentMessage.user("hi")),
                AgentSendOptions(sessionId = "s1"),
            ),
        )
    }

    @Test fun `images are routed away from runs, whose input is text-only`() {
        val caps = HermesCapabilities.LEGACY.copy(runSubmission = true, runEventsSse = true, detected = true)
        val withImage = listOf(AgentMessage.user("look", listOf(AgentAttachment("image/png", "AAAA"))))
        assertEquals(
            HermesTransport.CHAT_COMPLETIONS,
            client(transport = HermesTransportPreference.RUNS)
                .chooseTransport(caps, withImage, AgentSendOptions(sessionId = "s1")),
        )
    }

    @Test fun `an explicit preference is respected over feature detection`() {
        val caps = HermesCapabilities.LEGACY.copy(sessionChatStreaming = true, detected = true)
        assertEquals(
            HermesTransport.CHAT_COMPLETIONS,
            client(transport = HermesTransportPreference.CHAT_COMPLETIONS)
                .chooseTransport(caps, listOf(AgentMessage.user("hi")), AgentSendOptions(sessionId = "s1")),
        )
    }

    // ------------------------------------------------------------------
    // Runs event stream
    // ------------------------------------------------------------------

    @Test fun `run message deltas accumulate`() {
        val collected = StringBuilder()
        val c = client()
        val a = c.mapRunEvent(SseEvent(null, """{"event":"message.delta","run_id":"r1","delta":"Hel"}"""), collected, "r1")
        val b = c.mapRunEvent(SseEvent(null, """{"event":"message.delta","run_id":"r1","delta":"lo"}"""), collected, "r1")
        assertEquals("Hel", (a as AgentEvent.TokenDelta).text)
        assertEquals("lo", (b as AgentEvent.TokenDelta).text)
        assertEquals("Hello", collected.toString())
    }

    @Test fun `tool started carries the server's preview`() {
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"tool.started","run_id":"r1","tool":"web.search","preview":"q=hi"}"""),
            StringBuilder(),
            "r1",
        )
        val tp = mapped as AgentEvent.ToolProgress
        assertEquals("web.search", tp.tool)
        assertEquals("started", tp.stage)
        assertEquals("q=hi", tp.detail)
    }

    @Test fun `tool completed reports duration, not the boolean error flag`() {
        // `error` is a boolean here; showing it as the detail rendered "false"
        // under every finished tool.
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"tool.completed","run_id":"r1","tool":"shell","duration":1.25,"error":false}"""),
            StringBuilder(),
            "r1",
        )
        val tp = mapped as AgentEvent.ToolProgress
        assertEquals("completed", tp.stage)
        assertEquals("1.25s", tp.detail)
    }

    @Test fun `a failed tool is reported as failed`() {
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"tool.completed","run_id":"r1","tool":"shell","duration":0.4,"error":true}"""),
            StringBuilder(),
            "r1",
        )
        assertEquals("failed", (mapped as AgentEvent.ToolProgress).stage)
    }

    @Test fun `an approval request is surfaced with the choices the server offered`() {
        val mapped = client().mapRunEvent(
            SseEvent(
                null,
                """{"event":"approval.request","run_id":"r1","request_id":"a1","command":"rm -rf /tmp/x",
                   "description":"delete files","choices":["once","session","deny"]}""",
            ),
            StringBuilder(),
            "r1",
        )
        val approval = mapped as AgentEvent.ApprovalRequest
        assertEquals("r1", approval.runId)
        assertEquals("a1", approval.requestId)
        assertEquals("rm -rf /tmp/x", approval.command)
        assertEquals(listOf("once", "session", "deny"), approval.choices)
    }

    @Test fun `a cancelled run is an error, not a finished answer`() {
        val collected = StringBuilder("half an answer")
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"run.cancelled","run_id":"r1"}"""),
            collected,
            "r1",
        )
        assertTrue(mapped is AgentEvent.Error)
    }

    @Test fun `a failed run surfaces the server's message`() {
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"run.failed","error":"bad provider"}"""),
            StringBuilder(),
            "r1",
        )
        assertEquals("bad provider", (mapped as AgentEvent.Error).message)
    }

    @Test fun `reasoning is reported separately from the answer text`() {
        val collected = StringBuilder()
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"reasoning.available","text":"internal thoughts"}"""),
            collected,
            "r1",
        )
        assertTrue(mapped is AgentEvent.Reasoning)
        // Reasoning must never leak into the spoken/displayed answer.
        assertEquals("", collected.toString())
    }

    @Test fun `subagent activity is surfaced as tool progress`() {
        val mapped = client().mapRunEvent(
            SseEvent(null, """{"event":"subagent.start","run_id":"r1","goal":"research the API"}"""),
            StringBuilder(),
            "r1",
        )
        assertEquals("research the API", (mapped as AgentEvent.ToolProgress).detail)
    }

    // ------------------------------------------------------------------
    // Session chat event stream
    // ------------------------------------------------------------------

    @Test fun `session deltas accumulate and run completed ends the stream`() {
        val collected = StringBuilder()
        val c = client()
        c.mapSessionEvent(SseEvent("assistant.delta", """{"delta":"Hi ","message_id":"m1"}"""), collected)
        c.mapSessionEvent(SseEvent("assistant.delta", """{"delta":"there","message_id":"m1"}"""), collected)
        val done = c.mapSessionEvent(SseEvent("run.completed", """{"run_id":"r1","completed":true}"""), collected)
        assertEquals("Hi there", (done as AgentEvent.Completed).finalText)
        assertEquals("r1", done.runId)
    }

    @Test fun `assistant completed replaces the accumulated text with the final content`() {
        val collected = StringBuilder("partial")
        val c = client()
        c.mapSessionEvent(
            SseEvent("assistant.completed", """{"content":"the whole answer","interrupted":false}"""),
            collected,
        )
        assertEquals("the whole answer", collected.toString())
    }

    @Test fun `an interrupted session answer is reported as an error`() {
        val mapped = client().mapSessionEvent(
            SseEvent("assistant.completed", """{"content":"half","interrupted":true}"""),
            StringBuilder(),
        )
        assertTrue(mapped is AgentEvent.Error)
    }

    @Test fun `session thinking progress is reasoning, not a tool`() {
        val mapped = client().mapSessionEvent(
            SseEvent("tool.progress", """{"tool_name":"_thinking","delta":"considering"}"""),
            StringBuilder(),
        )
        assertTrue(mapped is AgentEvent.Reasoning)
    }

    @Test fun `session error events surface their message`() {
        val mapped = client().mapSessionEvent(SseEvent("error", """{"message":"boom"}"""), StringBuilder())
        assertEquals("boom", (mapped as AgentEvent.Error).message)
    }

    // ------------------------------------------------------------------
    // Chat-completions event stream
    // ------------------------------------------------------------------

    @Test fun `chat completion token deltas accumulate`() {
        val collected = StringBuilder()
        val c = client()
        c.mapChatCompletionEvent(SseEvent(null, """{"choices":[{"delta":{"content":"Hel"}}]}"""), collected)
        c.mapChatCompletionEvent(SseEvent(null, """{"choices":[{"delta":{"content":"lo"}}]}"""), collected)
        assertEquals("Hello", collected.toString())
    }

    @Test fun `DONE completes with the collected text`() {
        val mapped = client().mapChatCompletionEvent(SseEvent(null, "[DONE]"), StringBuilder("world"))
        assertEquals("world", (mapped as AgentEvent.Completed).finalText)
    }

    @Test fun `finish_reason stop completes cleanly`() {
        val mapped = client().mapChatCompletionEvent(
            SseEvent(null, """{"choices":[{"finish_reason":"stop","delta":{}}]}"""),
            StringBuilder("x"),
        )
        assertTrue(mapped is AgentEvent.Completed)
    }

    @Test fun `finish_reason error is not presented as a finished answer`() {
        val mapped = client().mapChatCompletionEvent(
            SseEvent(
                null,
                """{"choices":[{"finish_reason":"error","delta":{}}],
                   "hermes":{"completed":false,"failed":true,"error":"provider exploded"}}""",
            ),
            StringBuilder("partial"),
        )
        assertEquals("provider exploded", (mapped as AgentEvent.Error).message)
    }

    @Test fun `a truncated answer is reported rather than silently accepted`() {
        val mapped = client().mapChatCompletionEvent(
            SseEvent(null, """{"choices":[{"finish_reason":"length","delta":{}}]}"""),
            StringBuilder("partial"),
        )
        assertTrue(mapped is AgentEvent.Error)
    }

    @Test fun `the hermes tool progress event is mapped`() {
        val mapped = client().mapChatCompletionEvent(
            SseEvent("hermes.tool.progress", """{"tool":"web.search","stage":"started","detail":"q=hi"}"""),
            StringBuilder(),
        )
        assertEquals("web.search", (mapped as AgentEvent.ToolProgress).tool)
    }

    @Test fun `unknown events are ignored rather than breaking the stream`() {
        assertNull(client().mapRunEvent(SseEvent(null, """{"event":"future.thing"}"""), StringBuilder(), "r1"))
        assertNull(client().mapSessionEvent(SseEvent("future.thing", "{}"), StringBuilder()))
        assertNull(client().mapChatCompletionEvent(SseEvent(null, """{"object":"chat.completion.chunk"}"""), StringBuilder()))
    }
}
