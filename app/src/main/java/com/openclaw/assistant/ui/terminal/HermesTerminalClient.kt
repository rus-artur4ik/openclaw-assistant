package com.openclaw.assistant.ui.terminal

import android.content.Context
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object HermesTerminalClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun resolveEndpoint(context: Context, channel: String = "agentvoice-${UUID.randomUUID()}"): String? {
        return resolveHermesWebSocketEndpoint(context, "/api/pty", channel)
    }

    private fun resolveRpcEndpoint(context: Context, channel: String = "agentvoice-${UUID.randomUUID()}"): String? {
        return resolveHermesWebSocketEndpoint(context, "/api/ws", channel)
    }

    private fun resolveHermesWebSocketEndpoint(context: Context, endpointPath: String, channel: String): String? {
        val backend = BackendRepository.getInstance(context).backends.value.firstOrNull {
            it.enabled &&
                it.type == BackendType.HERMES_API_SERVER &&
                !it.terminalUrl.isNullOrBlank() &&
                !it.terminalSessionToken.isNullOrBlank()
        } ?: return null
        val base = backend.terminalUrl?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: return null
        val token = backend.terminalSessionToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path =
            if (base.encodedPath.endsWith(endpointPath)) {
                base.encodedPath
            } else {
                "${base.encodedPath.trimEnd('/')}$endpointPath"
            }
        val httpUrl = base.newBuilder()
            .encodedPath(path)
            .setQueryParameter("token", token)
            .setQueryParameter("channel", channel)
            .build()
        return httpUrl.toString().replaceFirst(
            if (base.isHttps) "https://" else "http://",
            if (base.isHttps) "wss://" else "ws://",
        )
    }

    suspend fun requestCommandRun(context: Context, command: String, settleMs: Long = 3_000L): Result<Unit> =
        submitPrompt(
            context = context,
            prompt = "Use the terminal tool to run this exact local command, then report whether it succeeded:\n\n```sh\n${command.trim()}\n```",
            settleMs = settleMs,
        )

    private suspend fun submitPrompt(context: Context, prompt: String, settleMs: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            val endpoint = resolveRpcEndpoint(context.applicationContext)
                ?: return@withContext Result.failure(IllegalStateException("Hermes Terminal is not connected. Set the Terminal URL on your Hermes backend in Settings."))
            val opened = CompletableDeferred<WebSocket>()
            val sessionCreated = CompletableDeferred<String>()
            val createRequestId = UUID.randomUUID().toString()
            val request = Request.Builder().url(endpoint).build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(webSocket)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!opened.isCompleted) opened.completeExceptionally(t)
                        if (!sessionCreated.isCompleted) sessionCreated.completeExceptionally(t)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (sessionCreated.isCompleted) return
                        runCatching {
                            val obj = JSONObject(text)
                            if (obj.optString("id") != createRequestId) return
                            val sessionId = obj.optJSONObject("result")
                                ?.optJSONObject("value")
                                ?.optString("session_id")
                                ?.trim()
                                .orEmpty()
                            if (sessionId.isNotEmpty()) {
                                sessionCreated.complete(sessionId)
                            }
                        }
                    }
                },
            )
            try {
                val ws = withTimeout(10_000L) { opened.await() }
                ws.send(jsonRpc("session.create", JSONObject().put("cols", 80), createRequestId))
                val sessionId = withTimeout(10_000L) { sessionCreated.await() }
                ws.send(jsonRpc("prompt.submit", JSONObject().put("session_id", sessionId).put("text", prompt)))
                delay(settleMs)
                ws.close(1000, "prompt submitted")
                Result.success(Unit)
            } catch (t: Throwable) {
                socket.cancel()
                Result.failure(t)
            }
        }

    suspend fun runCommand(context: Context, command: String, settleMs: Long = 3_000L): Result<Unit> =
        withContext(Dispatchers.IO) {
            val endpoint = resolveEndpoint(context.applicationContext)
                ?: return@withContext Result.failure(IllegalStateException("Hermes Terminal is not connected. Set the Terminal URL on your Hermes backend in Settings."))
            val opened = CompletableDeferred<WebSocket>()
            val closed = CompletableDeferred<Unit>()
            val request = Request.Builder().url(endpoint).build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(webSocket)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!opened.isCompleted) opened.completeExceptionally(t)
                        if (!closed.isCompleted) closed.completeExceptionally(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!closed.isCompleted) closed.complete(Unit)
                    }
                },
            )
            try {
                val ws = withTimeout(10_000L) { opened.await() }
                ws.send("\u001b[RESIZE:80;24]")
                delay(400L)
                ws.send(command.trimEnd() + "\n")
                delay(settleMs)
                ws.close(1000, "command sent")
                Result.success(Unit)
            } catch (t: Throwable) {
                socket.cancel()
                Result.failure(t)
            }
        }

    private fun jsonRpc(method: String, params: JSONObject, id: String = UUID.randomUUID().toString()): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
}
