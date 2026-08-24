package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.openclaw.assistant.api.OpenClawClient

/**
 * Adapter wrappers exposing the existing OpenClaw clients through [AgentClient].
 *
 * The existing OpenClaw Gateway and HTTP code paths are deeply integrated with the
 * UI, Voice Overlay, HotwordService, and node-capability stack, so they are NOT
 * routed through this interface today. These adapters exist so the rest of the
 * WakeClaw surface (Settings UI, Backend list, connection test) can treat all
 * backends uniformly.
 *
 * OpenClaw HTTP can send directly through [OpenClawClient]. OpenClaw Gateway is
 * Android-runtime bound, so Primary/Chat dispatch routes it through
 * [PrimaryBackendDispatcher] where a [android.content.Context] is available.
 */
class OpenClawGatewayAdapter(override val config: AgentBackendConfig) : AgentClient {
    override suspend fun testConnection(): ConnectionTestResult {
        val host = config.host?.trim().orEmpty()
        val port = config.port ?: 0
        return if (host.isNotEmpty() && port in 1..65535) {
            ConnectionTestResult(true, "Configured ($host:$port)")
        } else {
            ConnectionTestResult(false, "Missing host/port")
        }
    }
    override fun sendMessage(messages: List<AgentMessage>, options: AgentSendOptions): Flow<AgentEvent> = flow {
        emit(AgentEvent.Error("OpenClaw Gateway requires PrimaryBackendDispatcher because it needs the Android NodeRuntime"))
    }
}

class OpenClawHttpAdapter(override val config: AgentBackendConfig) : AgentClient {
    private val client = OpenClawClient()

    override suspend fun testConnection(): ConnectionTestResult {
        val url = config.baseUrl?.trim().orEmpty()
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            val started = System.currentTimeMillis()
            client.testConnection(url, config.apiKeyOrToken).fold(
                onSuccess = { ConnectionTestResult(true, "OK", System.currentTimeMillis() - started) },
                onFailure = { ConnectionTestResult(false, it.message ?: it.javaClass.simpleName) },
            )
        } else {
            ConnectionTestResult(false, "Invalid baseUrl")
        }
    }

    override fun sendMessage(messages: List<AgentMessage>, options: AgentSendOptions): Flow<AgentEvent> = flow {
        val url = config.baseUrl?.trim().orEmpty()
        if (url.isBlank()) {
            emit(AgentEvent.Error("OpenClaw HTTP baseUrl is not configured"))
            return@flow
        }
        val text = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content.orEmpty()
        if (text.isBlank()) {
            emit(AgentEvent.Error("OpenClaw HTTP message is empty"))
            return@flow
        }
        emit(AgentEvent.Started())
        val result = client.sendMessage(
            httpUrl = url,
            message = text,
            sessionId = options.sessionId ?: "agent-voice",
            authToken = config.apiKeyOrToken?.takeIf { it.isNotBlank() },
            agentId = options.extra["agentId"]?.takeIf { it.isNotBlank() },
            modelName = config.modelName?.takeIf { it.isNotBlank() },
        )
        result.fold(
            onSuccess = { response ->
                val finalText = response.getResponseText().orEmpty()
                emit(AgentEvent.Completed(finalText))
            },
            onFailure = { error ->
                emit(AgentEvent.Error(error.message ?: error.javaClass.simpleName, error))
            },
        )
    }
}
