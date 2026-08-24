package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.Flow

data class ConnectionTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long? = null,
)

interface AgentClient {
    val config: AgentBackendConfig
    suspend fun testConnection(): ConnectionTestResult
    fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions = AgentSendOptions(),
    ): Flow<AgentEvent>

    /** Cancels the run this client instance started, if it is still in flight. */
    suspend fun stopCurrentRun(): Boolean = false

    /**
     * Cancels a run by id. Callers that did not start the run themselves — a
     * Stop button rebuilt after process death, for instance — need this rather
     * than [stopCurrentRun], because clients are cheap and short-lived.
     */
    suspend fun stopRun(runId: String): Boolean = false

    /**
     * Answers an [AgentEvent.ApprovalRequest]. `choice` must be one of the
     * values the event offered (`once`, `session`, `always`, `deny`).
     */
    suspend fun respondToApproval(runId: String, choice: String): Boolean = false

    /** Redirects an in-flight run without cancelling it. */
    suspend fun steerRun(runId: String, text: String): Boolean = false
}
