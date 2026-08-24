package com.openclaw.assistant.backend

/**
 * Builds Hermes Agent API-server URLs from a user-entered base URL.
 *
 * The base is normalized into a `<scheme>://<host>[:<port>][/<prefix>]/v1` root.
 * Accepts `http://host:8642`, `.../v1`, either with a trailing slash, and a
 * reverse-proxy prefix such as `https://host/hermes/v1`.
 *
 * Two families hang off that root:
 *  - the OpenAI-compatible and Hermes-native `/v1/…` endpoints, and
 *  - the Hermes admin endpoints under `/api/…`, which sit next to `/v1`
 *    rather than inside it ([apiBase]).
 *
 * Only endpoints that the Hermes gateway actually serves are listed here —
 * see `gateway/platforms/api_server.py` route table in NousResearch/hermes-agent.
 */
internal object HermesUrl {
    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL is empty" }
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Base URL must start with http:// or https://"
        }
        return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    }

    /** Root that `/api/…` endpoints hang off — the normalized base minus the trailing `/v1`. */
    fun apiBase(base: String) = normalizeBase(base).removeSuffix("/v1")

    // ---- OpenAI-compatible surface -------------------------------------
    fun chatCompletionsUrl(base: String) = "${normalizeBase(base)}/chat/completions"
    fun responsesUrl(base: String) = "${normalizeBase(base)}/responses"
    fun modelsUrl(base: String) = "${normalizeBase(base)}/models"

    // ---- Discovery / health --------------------------------------------
    fun capabilitiesUrl(base: String) = "${normalizeBase(base)}/capabilities"
    fun skillsUrl(base: String) = "${normalizeBase(base)}/skills"
    fun toolsetsUrl(base: String) = "${normalizeBase(base)}/toolsets"

    /** `GET /v1/health`, served alongside the root `/health`. */
    fun healthUrl(base: String) = "${normalizeBase(base)}/health"
    fun rootHealthUrl(base: String) = "${apiBase(base)}/health"
    fun detailedHealthUrl(base: String) = "${apiBase(base)}/health/detailed"

    /**
     * Hermes provider/model inventory. Served by the API server itself under
     * the ordinary bearer key — not only by the separate dashboard process.
     */
    fun modelOptionsUrl(base: String, refresh: Boolean = false) =
        "${apiBase(base)}/api/model/options" + if (refresh) "?refresh=1" else ""

    /** Same endpoint on a Hermes *dashboard* origin, which authenticates by session token. */
    fun dashboardModelOptionsUrl(dashboardBase: String) =
        "${dashboardBase.trim().trimEnd('/')}/api/model/options"

    // ---- Runs API -------------------------------------------------------
    fun runsUrl(base: String) = "${normalizeBase(base)}/runs"
    fun runUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id"
    fun runEventsUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/events"
    fun runStopUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/stop"
    fun runApprovalUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/approval"
    fun runSteerUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/steer"

    // ---- Sessions API ---------------------------------------------------
    fun sessionsUrl(base: String) = "${apiBase(base)}/api/sessions"
    fun sessionUrl(base: String, id: String) = "${apiBase(base)}/api/sessions/$id"
    fun sessionMessagesUrl(base: String, id: String) = "${sessionUrl(base, id)}/messages"
    fun sessionForkUrl(base: String, id: String) = "${sessionUrl(base, id)}/fork"
    fun sessionChatUrl(base: String, id: String) = "${sessionUrl(base, id)}/chat"
    fun sessionChatStreamUrl(base: String, id: String) = "${sessionUrl(base, id)}/chat/stream"
    fun sessionModelUrl(base: String, id: String) = "${sessionUrl(base, id)}/model"

    // ---- Jobs API -------------------------------------------------------
    fun jobsUrl(base: String) = "${apiBase(base)}/api/jobs"
    fun jobUrl(base: String, id: String) = "${jobsUrl(base)}/$id"
    fun jobPauseUrl(base: String, id: String) = "${jobUrl(base, id)}/pause"
    fun jobResumeUrl(base: String, id: String) = "${jobUrl(base, id)}/resume"
    fun jobRunUrl(base: String, id: String) = "${jobUrl(base, id)}/run"
}
