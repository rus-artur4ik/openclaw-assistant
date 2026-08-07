package com.openclaw.assistant.talk

import com.openclaw.assistant.node.NodeRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject

/**
 * Thin RPC surface for gateway-relay Talk, on top of the operator socket [NodeRuntime] already owns.
 *
 * Every `talk.*` method needs the `operator.write` scope and is bound to the connection that created
 * the session, so this deliberately reuses `NodeRuntime.operatorSession` rather than opening its own.
 */
class TalkRelayClient(private val runtime: NodeRuntime) {

  /**
   * Run a gateway RPC, turning a transport-side cancellation into a catchable failure.
   *
   * When the socket drops, [com.openclaw.assistant.gateway.GatewaySession] cancels its pending
   * waiters, which would otherwise cancel *our* coroutine and skip the fallback to the on-device
   * engine entirely. A cancellation while our own scope is still active can only have come from the
   * transport, so it is re-thrown as an ordinary error.
   */
  private suspend fun request(method: String, paramsJson: String, timeoutMs: Long): String {
    return try {
      runtime.requestGateway(method, paramsJson, timeoutMs)
    } catch (err: CancellationException) {
      if (currentCoroutineContext().isActive) {
        throw IllegalStateException("Gateway connection closed during $method")
      }
      throw err
    }
  }

  /**
   * The params schema is a closed object, so only these four keys may be sent. Provider, model,
   * voice and ttlMs are accepted by the schema but silently ignored on the relay path — the voice
   * is whatever openclaw's own Talk config says.
   */
  suspend fun createSession(sessionKey: String?): TalkRelayProtocol.Session {
    val params =
      JSONObject().apply {
        put("mode", TalkRelayProtocol.MODE_REALTIME)
        put("transport", TalkRelayProtocol.TRANSPORT_GATEWAY_RELAY)
        put("brain", TalkRelayProtocol.BRAIN_AGENT_CONSULT)
        sessionKey?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionKey", it) }
      }
    val response = request(TalkRelayProtocol.METHOD_CREATE, params.toString(), timeoutMs = 20_000)
    return TalkRelayProtocol.parseSession(response)
      ?: throw IllegalStateException("talk.session.create returned no relay session")
  }

  /**
   * Uplink audio. Fire-and-forget on purpose: at ~25 chunks/second an awaited ack per chunk would
   * keep dozens of coroutines and 15s timers alive at all times.
   *
   * @return false when the socket is gone, so the caller can tear the session down.
   */
  suspend fun appendAudio(relaySessionId: String, audioBase64: String, timestampMs: Long): Boolean {
    val params =
      JSONObject().apply {
        put("sessionId", relaySessionId)
        put("audioBase64", audioBase64)
        put("timestamp", timestampMs)
      }
    return runtime.sendGatewayNoWait(TalkRelayProtocol.METHOD_APPEND_AUDIO, params.toString())
  }

  suspend fun cancelOutput(relaySessionId: String, reason: String) {
    val params =
      JSONObject().apply {
        put("sessionId", relaySessionId)
        put("reason", reason)
      }
    request(TalkRelayProtocol.METHOD_CANCEL_OUTPUT, params.toString(), timeoutMs = 5_000)
  }

  /**
   * Hand a tool result back to the relay. With [willContinue] the gateway keeps the call open and,
   * on this deployment's STT-TTS cascade, speaks a filler phrase while the agent works.
   */
  suspend fun submitToolResult(
    relaySessionId: String,
    callId: String,
    result: JSONObject,
    willContinue: Boolean = false,
  ) {
    val params =
      JSONObject().apply {
        put("sessionId", relaySessionId)
        put("callId", callId)
        put("result", result)
        if (willContinue) put("options", JSONObject().put("willContinue", true))
      }
    request(TalkRelayProtocol.METHOD_SUBMIT_TOOL_RESULT, params.toString(), timeoutMs = 10_000)
  }

  /** Starts the agent run that answers an `openclaw_agent_consult` tool call. Returns its runId. */
  suspend fun startAgentConsult(
    sessionKey: String,
    relaySessionId: String,
    callId: String,
    argsJson: String,
  ): String {
    val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
    val params =
      JSONObject().apply {
        put("sessionKey", sessionKey)
        put("callId", callId)
        put("name", TalkRelayProtocol.AGENT_CONSULT_TOOL)
        put("args", args)
        put("relaySessionId", relaySessionId)
      }
    val response =
      request(TalkRelayProtocol.METHOD_CLIENT_TOOL_CALL, params.toString(), timeoutMs = 30_000)
    val root = runCatching { JSONObject(response) }.getOrNull()
    return root?.optString("runId")?.takeIf { it.isNotBlank() }
      ?: root?.optString("idempotencyKey")?.takeIf { it.isNotBlank() }
      ?: throw IllegalStateException("talk.client.toolCall returned no run id")
  }

  suspend fun abortRun(sessionKey: String, runId: String) {
    val params =
      JSONObject().apply {
        put("sessionKey", sessionKey)
        put("runId", runId)
      }
    runCatching { request("chat.abort", params.toString(), timeoutMs = 5_000) }
  }

  suspend fun closeSession(relaySessionId: String) {
    val params = JSONObject().put("sessionId", relaySessionId)
    runCatching { request(TalkRelayProtocol.METHOD_CLOSE, params.toString(), timeoutMs = 5_000) }
  }

  /** Redacted Talk configuration for the settings screen. Never requests `includeSecrets`. */
  suspend fun fetchConfig(): TalkConfigSummary? {
    val response =
      runCatching { request(TalkRelayProtocol.METHOD_CONFIG, "{}", timeoutMs = 10_000) }
        .getOrNull() ?: return null
    return TalkConfigSummary.parse(response)
  }
}
