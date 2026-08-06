package com.openclaw.assistant.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class VoiceTurnTimings(private val now: () -> Long = System::currentTimeMillis) {
    private var speechEndAt = 0L
    private var transcriptAt = 0L
    private var sendAt = 0L
    private var replyAt = 0L
    private var ttsStartAt = 0L
    private var firstAudioAt = 0L
    private var ttsDoneAt = 0L
    private var transcriptChars = 0
    private var replyChars = 0
    private var reported = false

    fun onSpeechEnd() {
        if (speechEndAt == 0L) speechEndAt = now()
    }

    fun onTranscript(chars: Int) {
        transcriptAt = now()
        transcriptChars = chars
    }

    fun onSend() {
        sendAt = now()
    }

    fun onReply(chars: Int) {
        replyAt = now()
        replyChars = chars
    }

    fun onTtsStart() {
        if (ttsStartAt == 0L) ttsStartAt = now()
    }

    fun onFirstAudio() {
        if (firstAudioAt == 0L) firstAudioAt = now()
    }

    fun onTtsDone() {
        ttsDoneAt = now()
    }

    fun buildReportJson(sessionKey: String, sttProvider: String?, ttsProvider: String?): String? {
        if (reported || sessionKey.isBlank() || sendAt == 0L || replyAt < sendAt) return null
        reported = true
        val json = buildJsonObject {
            put("sessionKey", JsonPrimitive(sessionKey))
            put("llmMs", JsonPrimitive(replyAt - sendAt))
            durationOrNull(speechEndAt, transcriptAt)?.let { put("sttMs", JsonPrimitive(it)) }
            durationOrNull(transcriptAt, sendAt)?.let { put("dispatchGapMs", JsonPrimitive(it)) }
            durationOrNull(replyAt, ttsStartAt)?.let { put("ttsPrepMs", JsonPrimitive(it)) }
            durationOrNull(ttsStartAt, firstAudioAt)?.let { put("ttsTtfaMs", JsonPrimitive(it)) }
            durationOrNull(firstAudioAt, ttsDoneAt)?.let { put("ttsStreamMs", JsonPrimitive(it)) }
            if (transcriptChars > 0) put("transcriptChars", JsonPrimitive(transcriptChars))
            if (replyChars > 0) put("textChars", JsonPrimitive(replyChars))
            sttProvider?.let { put("sttProvider", JsonPrimitive(it)) }
            ttsProvider?.let { put("ttsProvider", JsonPrimitive(it)) }
        }
        return json.toString()
    }

    private fun durationOrNull(from: Long, to: Long): Long? =
        if (from > 0L && to >= from) to - from else null
}
