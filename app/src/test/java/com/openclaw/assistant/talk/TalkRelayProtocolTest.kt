package com.openclaw.assistant.talk

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Payloads here are copied from the running gateway (openclaw 2026.7.2-beta.7 with the local
 * stt-tts cascade patch), not invented, so a protocol change shows up as a test failure.
 *
 * Robolectric because the parser uses `org.json`, which is a stub in the plain JVM test runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TalkRelayProtocolTest {

  @Test
  fun `parses a cascade create response`() {
    val json =
      """
      {"provider":"openai","transport":"gateway-relay","relaySessionId":"a1b2",
       "audio":{"inputEncoding":"pcm16","inputSampleRateHz":24000,
                "outputEncoding":"pcm16","outputSampleRateHz":24000},
       "expiresAt":1785000000,"sessionId":"a1b2","mode":"realtime","brain":"agent-consult"}
      """.trimIndent()

    val session = TalkRelayProtocol.parseSession(json)!!

    assertEquals("a1b2", session.relaySessionId)
    assertEquals("openai", session.provider)
    assertEquals(24_000, session.inputSampleRateHz)
    assertEquals(24_000, session.outputSampleRateHz)
    assertEquals(1_785_000_000L, session.expiresAtSeconds)
    assertTrue(session.usesPcm16)
    // The cascade omits voiceSessionId; `mode` echoes the request and cannot discriminate.
    assertNull(session.voiceSessionId)
    assertEquals("stt-tts cascade", session.engineLabel)
  }

  @Test
  fun `distinguishes the provider-native realtime path by voiceSessionId`() {
    val json =
      """
      {"provider":"openai","transport":"gateway-relay","relaySessionId":"c3","voiceSessionId":"c3",
       "audio":{"inputEncoding":"pcm16","inputSampleRateHz":24000,
                "outputEncoding":"pcm16","outputSampleRateHz":24000},
       "expiresAt":1785000000,"sessionId":"c3","mode":"realtime","brain":"agent-consult"}
      """.trimIndent()

    assertEquals("provider-realtime", TalkRelayProtocol.parseSession(json)!!.engineLabel)
  }

  @Test
  fun `rejects a session without a relay id`() {
    assertNull(TalkRelayProtocol.parseSession("""{"provider":"openai"}"""))
    assertNull(TalkRelayProtocol.parseSession("not json"))
  }

  @Test
  fun `flags a non-pcm16 negotiation instead of streaming garbage`() {
    val json =
      """
      {"relaySessionId":"x","audio":{"inputEncoding":"opus","inputSampleRateHz":48000,
       "outputEncoding":"pcm16","outputSampleRateHz":24000}}
      """.trimIndent()

    assertFalse(TalkRelayProtocol.parseSession(json)!!.usesPcm16)
  }

  @Test
  fun `parses each relay event type`() {
    fun parse(payload: String) = TalkRelayProtocol.parseEvent(payload)

    assertTrue(parse("""{"relaySessionId":"s","type":"ready"}""") is TalkRelayProtocol.Event.Ready)
    assertTrue(parse("""{"relaySessionId":"s","type":"clear"}""") is TalkRelayProtocol.Event.Clear)
    assertTrue(parse("""{"relaySessionId":"s","type":"audioDone"}""") is TalkRelayProtocol.Event.AudioDone)

    val transcript =
      parse("""{"relaySessionId":"s","type":"transcript","role":"user","text":"привет","final":true}""")
        as TalkRelayProtocol.Event.Transcript
    assertEquals("user", transcript.role)
    assertEquals("привет", transcript.text)
    assertTrue(transcript.final)

    val audio =
      parse("""{"relaySessionId":"s","type":"audio","audioBase64":"AAECAw=="}""")
        as TalkRelayProtocol.Event.Audio
    assertEquals("AAECAw==", audio.audioBase64)

    val failure =
      parse("""{"relaySessionId":"s","type":"error","message":"boom"}""")
        as TalkRelayProtocol.Event.Failure
    assertEquals("boom", failure.message)

    val closed =
      parse("""{"relaySessionId":"s","type":"close","reason":"completed"}""")
        as TalkRelayProtocol.Event.Closed
    assertEquals("completed", closed.reason)
  }

  @Test
  fun `parses the forced agent-consult tool call`() {
    val payload =
      """
      {"relaySessionId":"s","type":"toolCall","itemId":"i1","callId":"c1","forced":true,
       "name":"openclaw_agent_consult","args":{"question":"какая погода"}}
      """.trimIndent()

    val call = TalkRelayProtocol.parseEvent(payload) as TalkRelayProtocol.Event.ToolCall

    assertEquals("c1", call.callId)
    assertEquals(TalkRelayProtocol.AGENT_CONSULT_TOOL, call.name)
    assertTrue(call.forced)
    assertTrue(call.argsJson.contains("какая погода"))
  }

  @Test
  fun `treats tool progress as non-final so the consult is not cancelled early`() {
    val progress =
      """
      {"relaySessionId":"s","type":"toolResult","callId":"c1",
       "talkEvent":{"type":"tool.progress","payload":{"status":"working"}}}
      """.trimIndent()
    val nonFinalResult =
      """
      {"relaySessionId":"s","type":"toolResult","callId":"c1",
       "talkEvent":{"type":"tool.result","final":false}}
      """.trimIndent()
    val finalResult = """{"relaySessionId":"s","type":"toolResult","callId":"c1"}"""

    assertFalse((TalkRelayProtocol.parseEvent(progress) as TalkRelayProtocol.Event.ToolResult).final)
    assertFalse((TalkRelayProtocol.parseEvent(nonFinalResult) as TalkRelayProtocol.Event.ToolResult).final)
    assertTrue((TalkRelayProtocol.parseEvent(finalResult) as TalkRelayProtocol.Event.ToolResult).final)
  }

  @Test
  fun `ignores unknown and malformed events`() {
    assertTrue(
      TalkRelayProtocol.parseEvent("""{"relaySessionId":"s","type":"inputAudio","byteLength":4}""")
        is TalkRelayProtocol.Event.Unknown
    )
    assertNull(TalkRelayProtocol.parseEvent("""{"type":"ready"}"""))
    assertNull(TalkRelayProtocol.parseEvent(null))
    assertNull(TalkRelayProtocol.parseEvent("{"))
    // An audio frame with no payload must not reach the player as an empty buffer.
    assertNull(TalkRelayProtocol.parseEvent("""{"relaySessionId":"s","type":"audio"}"""))
  }

  @Test
  fun `reads the consult answer out of a terminal chat event`() {
    val payload =
      """
      {"runId":"run-1","sessionKey":"agent:talk:x","seq":3,"state":"final",
       "message":{"role":"assistant","content":[{"type":"text","text":"Готово"}]}}
      """.trimIndent()

    val outcome = TalkRelayProtocol.parseChatFinalText(payload, "run-1")

    assertEquals(TalkRelayProtocol.ChatRunOutcome.Final("Готово"), outcome)
  }

  @Test
  fun `ignores chat events for other runs and non-terminal states`() {
    val other =
      """{"runId":"run-2","state":"final","message":{"content":[{"type":"text","text":"x"}]}}"""
    val streaming = """{"runId":"run-1","state":"delta"}"""

    assertNull(TalkRelayProtocol.parseChatFinalText(other, "run-1"))
    assertNull(TalkRelayProtocol.parseChatFinalText(streaming, "run-1"))
  }

  @Test
  fun `surfaces aborted and failed consult runs`() {
    assertEquals(
      TalkRelayProtocol.ChatRunOutcome.Aborted,
      TalkRelayProtocol.parseChatFinalText("""{"runId":"r","state":"aborted"}""", "r"),
    )
    assertEquals(
      TalkRelayProtocol.ChatRunOutcome.Failed("nope"),
      TalkRelayProtocol.parseChatFinalText("""{"runId":"r","state":"error","errorMessage":"nope"}""", "r"),
    )
  }

  @Test
  fun `a final run with no text is reported as empty rather than dropped`() {
    val outcome = TalkRelayProtocol.parseChatFinalText("""{"runId":"r","state":"final"}""", "r")

    assertEquals(TalkRelayProtocol.ChatRunOutcome.Final(""), outcome)
  }

  // --- extractSpokenText: what actually gets synthesized -------------------------------------

  @Test
  fun `an inline tts directive with prose wins over the surrounding reasoning`() {
    // The reply that produced the double-speech bug, verbatim from the device.
    val reply =
      """
      В Берлине сейчас **06:34** утра.

      Берлин на час впереди Бангкока (UTC+2 летом, а Бангкок UTC+7, разница −5
      часов... погодите). На самом деле, Берлин на 5 часов позади Бангкока. 11:34 − 5 = 06:34.

      [[tts:В Берлине сейчас 6 часов 34 минуты утра.]]
      """.trimIndent()

    assertEquals("В Берлине сейчас 6 часов 34 минуты утра.", TalkRelayProtocol.extractSpokenText(reply))
  }

  @Test
  fun `a hidden tts text block wins over everything else`() {
    val reply = "Развёрнутый ответ.\n[[tts:text]]Короткий устный ответ.[[/tts:text]]\n[[tts:Другое.]]"

    assertEquals("Короткий устный ответ.", TalkRelayProtocol.extractSpokenText(reply))
  }

  @Test
  fun `a visible tts block is spoken`() {
    val reply = "Вступление. [[tts]]Это и произносим.[[/tts]] Хвост."

    assertEquals("Это и произносим.", TalkRelayProtocol.extractSpokenText(reply))
  }

  @Test
  fun `parameter-only tts tags are stripped, not spoken`() {
    val reply = "Ответ голосом по умолчанию. [[tts:provider=elevenlabs speakerVoice=anna]]"

    assertEquals("Ответ голосом по умолчанию.", TalkRelayProtocol.extractSpokenText(reply))
  }

  @Test
  fun `a reply without directives is spoken as-is`() {
    assertEquals("Просто ответ.", TalkRelayProtocol.extractSpokenText("Просто ответ."))
  }

  @Test
  fun `prose containing an equals sign is still prose`() {
    val reply = "Пояснение. [[tts:Одиннадцать минус пять = шесть часов утра.]]"

    assertEquals("Одиннадцать минус пять = шесть часов утра.", TalkRelayProtocol.extractSpokenText(reply))
  }

  @Test
  fun `multiple inline directives are joined in order`() {
    val reply = "[[tts:Первое.]] шум [[tts:Второе.]]"

    assertEquals("Первое. Второе.", TalkRelayProtocol.extractSpokenText(reply))
  }
}
