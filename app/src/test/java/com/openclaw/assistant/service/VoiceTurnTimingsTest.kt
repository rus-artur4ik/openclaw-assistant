package com.openclaw.assistant.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTurnTimingsTest {

    private fun timingsAt(clock: MutableList<Long>): VoiceTurnTimings =
        VoiceTurnTimings(now = { clock.removeAt(0) })

    @Test
    fun `full turn produces all stage durations`() {
        val timings = timingsAt(mutableListOf(1_000L, 1_400L, 1_450L, 6_450L, 6_500L, 7_300L, 9_300L))
        timings.onSpeechEnd()
        timings.onTranscript(chars = 42)
        timings.onSend()
        timings.onReply(chars = 120)
        timings.onTtsStart()
        timings.onFirstAudio()
        timings.onTtsDone()

        val json = Json.parseToJsonElement(
            timings.buildReportJson("agent:talk:s1", sttProvider = "android", ttsProvider = "elevenlabs")!!,
        ).jsonObject

        assertEquals(400L, json["sttMs"]!!.jsonPrimitive.long)
        assertEquals(50L, json["dispatchGapMs"]!!.jsonPrimitive.long)
        assertEquals(5_000L, json["llmMs"]!!.jsonPrimitive.long)
        assertEquals(50L, json["ttsPrepMs"]!!.jsonPrimitive.long)
        assertEquals(800L, json["ttsTtfaMs"]!!.jsonPrimitive.long)
        assertEquals(2_000L, json["ttsStreamMs"]!!.jsonPrimitive.long)
        assertEquals(42L, json["transcriptChars"]!!.jsonPrimitive.long)
        assertEquals(120L, json["textChars"]!!.jsonPrimitive.long)
        assertEquals("agent:talk:s1", json["sessionKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing speech end and tts stages are omitted`() {
        val timings = timingsAt(mutableListOf(2_000L, 2_010L, 8_000L))
        timings.onTranscript(chars = 10)
        timings.onSend()
        timings.onReply(chars = 5)

        val json = Json.parseToJsonElement(
            timings.buildReportJson("agent:talk:s1", sttProvider = null, ttsProvider = null)!!,
        ).jsonObject

        assertEquals(5_990L, json["llmMs"]!!.jsonPrimitive.long)
        assertNull(json["sttMs"])
        assertNull(json["ttsTtfaMs"])
        assertNull(json["ttsStreamMs"])
        assertNull(json["sttProvider"])
    }

    @Test
    fun `reports only once and requires a reply`() {
        val incomplete = timingsAt(mutableListOf(1_000L))
        incomplete.onSend()
        assertNull(incomplete.buildReportJson("agent:talk:s1", null, null))

        val complete = timingsAt(mutableListOf(1_000L, 2_000L))
        complete.onSend()
        complete.onReply(chars = 3)
        assertEquals(false, complete.buildReportJson("agent:talk:s1", null, null) == null)
        assertNull(complete.buildReportJson("agent:talk:s1", null, null))
    }
}
