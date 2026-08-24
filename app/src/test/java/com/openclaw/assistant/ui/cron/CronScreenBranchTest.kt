package com.openclaw.assistant.ui.cron

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.ui.BackendTestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Cron tab is explicitly split in two, and each half has to say something
 * useful when its product is not configured — rather than showing the other
 * product's list or an empty screen with no explanation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class CronScreenBranchTest {

    @get:Rule(order = 0) val backends = BackendTestEnv()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show() = compose.setContent { CronScreen() }

    @Test fun `both owners are always offered`() {
        show()
        compose.onNodeWithText("HermesAgent").assertIsDisplayed()
        compose.onNodeWithText("OpenClaw").assertIsDisplayed()
    }

    @Test fun `hermes explains itself when no hermes backend exists`() {
        backends.gateway(primary = true)
        show()
        compose.onNodeWithText("HermesAgent cron is not configured").assertIsDisplayed()
    }

    @Test fun `openclaw explains itself when no openclaw backend exists`() {
        backends.hermes(primary = true)
        show()
        compose.onNodeWithText("OpenClaw").performClick()
        compose.onNodeWithText("No enabled OpenClaw backend is configured.").assertIsDisplayed()
    }

    @Test fun `a disabled hermes backend does not count as configured`() {
        val hermes = backends.hermes(primary = true)
        backends.gateway()
        backends.repo.setEnabled(hermes.id, false)
        show()
        compose.onNodeWithText("HermesAgent cron is not configured").assertIsDisplayed()
    }

    @Test fun `switching to the openclaw half drops the hermes empty state`() {
        backends.hermes(primary = true)
        show()
        compose.onNodeWithText("OpenClaw").performClick()
        compose.onNodeWithText("HermesAgent cron is not configured").assertDoesNotExist()
    }
}

/**
 * Which backend owns each half of the tab.
 *
 * Kept out of the Compose test because rendering a populated OpenClaw list
 * needs the real Android node runtime.
 */
class CronOwnersTest {

    private fun backend(id: String, type: BackendType, enabled: Boolean = true) = AgentBackendConfig(
        id = id,
        displayName = id,
        type = type,
        enabled = enabled,
        baseUrl = "http://$id.test",
    )

    @Test fun `the hermes half needs an enabled hermes backend`() {
        val hermes = backend("hm", BackendType.HERMES_API_SERVER)
        assertEquals("hm", CronOwners.hermesBackend(listOf(hermes))?.id)
        assertNull(CronOwners.hermesBackend(listOf(backend("hm", BackendType.HERMES_API_SERVER, enabled = false))))
        assertNull(CronOwners.hermesBackend(listOf(backend("gw", BackendType.OPENCLAW_GATEWAY))))
    }

    @Test fun `both openclaw kinds satisfy the openclaw half`() {
        // Counting only the gateway would hide scheduled jobs on an
        // HTTP-only install.
        assertEquals(
            listOf("gw", "api"),
            CronOwners.openClawBackends(
                listOf(
                    backend("gw", BackendType.OPENCLAW_GATEWAY),
                    backend("api", BackendType.OPENCLAW_HTTP),
                    backend("hm", BackendType.HERMES_API_SERVER),
                ),
            ).map { it.id },
        )
    }

    @Test fun `a disabled openclaw backend does not count`() {
        assertTrue(
            CronOwners.openClawBackends(
                listOf(backend("gw", BackendType.OPENCLAW_GATEWAY, enabled = false)),
            ).isEmpty(),
        )
    }

    @Test fun `neither half is satisfied by an empty list`() {
        assertNull(CronOwners.hermesBackend(emptyList()))
        assertTrue(CronOwners.openClawBackends(emptyList()).isEmpty())
    }
}
