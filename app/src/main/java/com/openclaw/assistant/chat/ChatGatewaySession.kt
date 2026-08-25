package com.openclaw.assistant.chat

/**
 * The slice of the gateway connection that chat needs.
 *
 * Narrow on purpose: it keeps [ChatController]'s health/session bookkeeping testable without
 * standing up a real socket, and the transport stays free to change behind it.
 */
interface ChatGatewaySession {
  suspend fun request(method: String, paramsJson: String?, timeoutMs: Long = 15_000): String

  suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean
}
