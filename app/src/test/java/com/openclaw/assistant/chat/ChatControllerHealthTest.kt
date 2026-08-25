package com.openclaw.assistant.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health is what the whole app reads to decide the gateway is reachable: a stale `false` blocks
 * voice turns and chat sends with "gateway not connected" while the socket is actually fine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerHealthTest {

  private class FakeSession(
    var healthFails: Boolean = false,
  ) : ChatGatewaySession {
    val requested = mutableListOf<String>()

    override suspend fun request(method: String, paramsJson: String?, timeoutMs: Long): String {
      requested += method
      if (method == "health" && healthFails) throw IllegalStateException("not connected")
      return "{}"
    }

    override suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean = true
  }

  private fun controller(scope: TestScope, session: ChatGatewaySession) =
    ChatController(
      scope = scope,
      session = session,
      json = Json { ignoreUnknownKeys = true },
      supportsChatSubscribe = false,
    )

  @Test
  fun healthStartsFalseBeforeAnyConnection() = runTest {
    val controller = controller(this, FakeSession())

    assertFalse(controller.healthOk.value)
  }

  @Test
  fun connectingMarksTheGatewayHealthy() = runTest(StandardTestDispatcher()) {
    val session = FakeSession()
    val controller = controller(this, session)

    controller.onConnected()
    advanceUntilIdle()

    assertTrue(controller.healthOk.value)
    assertEquals(listOf("health"), session.requested)
  }

  /** The regression: disconnect cleared health, and reconnecting never restored it. */
  @Test
  fun reconnectingRestoresHealthAfterADisconnect() = runTest(StandardTestDispatcher()) {
    val session = FakeSession()
    val controller = controller(this, session)
    controller.onConnected()
    advanceUntilIdle()

    controller.onDisconnected("connection lost")
    assertFalse(controller.healthOk.value)

    controller.onConnected()
    advanceUntilIdle()

    assertTrue(controller.healthOk.value)
  }

  /** Reconnects arrive well inside the 10s poll throttle, so the poll has to be forced. */
  @Test
  fun reconnectPollsEvenWhenTheLastPollWasRecent() = runTest(StandardTestDispatcher()) {
    val session = FakeSession()
    val controller = controller(this, session)
    controller.onConnected()
    advanceUntilIdle()
    controller.onDisconnected("connection lost")

    controller.onConnected()
    advanceUntilIdle()

    assertEquals(listOf("health", "health"), session.requested)
  }

  @Test
  fun connectingDoesNotClaimHealthWhenTheProbeFails() = runTest(StandardTestDispatcher()) {
    val session = FakeSession(healthFails = true)
    val controller = controller(this, session)

    controller.onConnected()
    advanceUntilIdle()

    assertFalse(controller.healthOk.value)
  }

  @Test
  fun aBootstrapAlreadyInFlightPollsHealthOnlyOnce() = runTest(StandardTestDispatcher()) {
    val session = FakeSession()
    val controller = controller(this, session)

    controller.load("main")
    controller.onConnected()
    advanceUntilIdle()

    assertEquals(1, session.requested.count { it == "health" })
    assertTrue(controller.healthOk.value)
  }
}
