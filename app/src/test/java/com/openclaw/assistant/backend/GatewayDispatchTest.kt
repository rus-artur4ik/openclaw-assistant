package com.openclaw.assistant.backend

import androidx.test.core.app.ApplicationProvider
import com.openclaw.assistant.ui.BackendTestEnv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gateway leg of the shared dispatcher.
 *
 * Unlike Hermes, the gateway does not answer the send call — the reply appears
 * later in a transcript the runtime owns, and the dispatcher polls for it. That
 * loop, its error hand-off and its timeout are the part a Hermes-only tester
 * would never touch, and they had no coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GatewayDispatchTest {

    @get:Rule val backends = BackendTestEnv()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** A stand-in transcript the test drives directly. */
    private class FakeGateway(
        var healthy: Boolean = true,
        var error: String? = null,
    ) : PrimaryBackendDispatcher.GatewayChatPort {
        val replies = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String?>>()
        /** Polls after the send before the agent's answer appears. */
        var replyAfterPolls: Int = 0
        var pendingReply: String? = null
        private var pollsSinceSend = -1

        override fun isHealthy() = healthy
        override fun currentError() = error
        override fun assistantReplies(): List<String> {
            // The dispatcher takes one snapshot before sending; only polls after
            // that count towards the agent "thinking".
            if (pollsSinceSend >= 0) {
                pollsSinceSend++
                pendingReply?.let {
                    if (pollsSinceSend > replyAfterPolls) {
                        replies += it
                        pendingReply = null
                    }
                }
            }
            return replies.toList()
        }
        override suspend fun send(message: String, modelName: String?) {
            sent += message to modelName
            pollsSinceSend = 0
        }
    }

    private var realFactory: ((android.content.Context) -> PrimaryBackendDispatcher.GatewayChatPort)? = null

    private fun install(gateway: FakeGateway) {
        if (realFactory == null) realFactory = PrimaryBackendDispatcher.gatewayPortFactory
        PrimaryBackendDispatcher.gatewayPortFactory = { gateway }
    }

    @After fun tearDown() {
        // The dispatcher is a singleton shared with every other test in this JVM.
        realFactory?.let { PrimaryBackendDispatcher.gatewayPortFactory = it }
    }

    private fun gatewayBackend(model: String? = "openclaw") = backends.gateway(primary = true, model = model)

    @Test fun `a reply that arrives is returned`() = runTest {
        gatewayBackend()
        install(FakeGateway().apply { pendingReply = "the answer"; replyAfterPolls = 0 })

        val reply = PrimaryBackendDispatcher.send(context, "hello")

        assertEquals("the answer", reply?.text)
        assertEquals("My OpenClaw", reply?.sourceDisplayName)
    }

    @Test fun `a reply that takes a few polls is still returned`() = runTest {
        gatewayBackend()
        install(FakeGateway().apply { pendingReply = "eventually"; replyAfterPolls = 5 })

        assertEquals("eventually", PrimaryBackendDispatcher.send(context, "hello")?.text)
    }

    @Test fun `the configured model is forwarded`() = runTest {
        gatewayBackend(model = "sonnet")
        val gateway = FakeGateway().apply { pendingReply = "ok" }
        install(gateway)

        PrimaryBackendDispatcher.send(context, "hello")

        assertEquals("hello" to "sonnet", gateway.sent.single())
    }

    @Test fun `a blank model is sent as no override`() = runTest {
        gatewayBackend(model = "   ")
        val gateway = FakeGateway().apply { pendingReply = "ok" }
        install(gateway)

        PrimaryBackendDispatcher.send(context, "hello")

        assertNull(gateway.sent.single().second)
    }

    @Test fun `an unhealthy gateway fails before sending anything`() = runTest {
        gatewayBackend()
        val gateway = FakeGateway(healthy = false)
        install(gateway)

        val error = runCatching { PrimaryBackendDispatcher.send(context, "hello") }.exceptionOrNull()

        assertTrue("was ${error?.message}", error?.message.orEmpty().contains("not connected"))
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun `a runtime error is surfaced instead of waiting out the timeout`() = runTest {
        gatewayBackend()
        install(FakeGateway(error = "agent authentication failed"))

        val error = runCatching { PrimaryBackendDispatcher.send(context, "hello") }.exceptionOrNull()

        assertEquals("agent authentication failed", error?.message)
    }

    @Test fun `silence past the timeout is reported as a missing reply`() = runTest {
        gatewayBackend()
        install(FakeGateway())

        val error = runCatching { PrimaryBackendDispatcher.send(context, "hello") }.exceptionOrNull()

        assertTrue(
            "was ${error?.message}",
            error?.message.orEmpty().contains("did not return a reply"),
        )
    }

    @Test fun `a blank reply is not mistaken for an answer`() = runTest {
        gatewayBackend()
        install(FakeGateway().apply { pendingReply = "   " })

        val error = runCatching { PrimaryBackendDispatcher.send(context, "hello") }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("did not return a reply"))
    }

    @Test fun `replies already in the transcript are not returned as this turn's answer`() = runTest {
        // The gateway keeps the whole conversation; without the count check the
        // dispatcher would immediately hand back the previous answer.
        gatewayBackend()
        val gateway = FakeGateway().apply {
            replies += "an older answer"
            pendingReply = "the new answer"
            replyAfterPolls = 3
        }
        install(gateway)

        assertEquals("the new answer", PrimaryBackendDispatcher.send(context, "hello")?.text)
    }

    @Test fun `an explicit gateway backend id is honoured`() = runTest {
        backends.hermes(primary = true)
        val gateway = backends.gateway(id = "gw-2")
        install(FakeGateway().apply { pendingReply = "from the gateway" })

        val reply = PrimaryBackendDispatcher.send(context, "hello", backendId = gateway.id)

        assertEquals("from the gateway", reply?.text)
    }
}
