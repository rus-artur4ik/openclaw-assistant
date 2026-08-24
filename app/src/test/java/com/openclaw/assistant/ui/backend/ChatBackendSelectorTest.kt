package com.openclaw.assistant.ui.backend

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openclaw.assistant.ui.BackendTestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The top-bar chip that names where the next message goes.
 *
 * It is the only always-visible indication of which branch is active, so a
 * label that lies is worse than no label at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class ChatBackendSelectorTest {

    @get:Rule(order = 0) val backends = BackendTestEnv()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show() = compose.setContent { ChatBackendSelector() }

    @Test fun `with nothing configured it says so`() {
        show()
        compose.onNodeWithText("No backend").assertIsDisplayed()
    }

    @Test fun `following the primary names the primary`() {
        backends.hermes(primary = true)
        backends.gateway()
        show()
        compose.onNodeWithText("Primary: My Hermes").assertIsDisplayed()
    }

    @Test fun `an explicit gateway override is named directly`() {
        val gateway = backends.gateway()
        backends.hermes(primary = true)
        ChatBackendTarget.set(gateway.id)
        show()
        compose.onNodeWithText("My OpenClaw").assertIsDisplayed()
    }

    @Test fun `picking the other branch updates the target`() {
        backends.hermes(primary = true)
        val gateway = backends.gateway()
        show()

        compose.onNodeWithText("Primary: My Hermes").performClick()
        compose.onNodeWithText("My OpenClaw").performClick()

        assertEquals(gateway.id, ChatBackendTarget.selectedId.value)
    }

    @Test fun `picking Primary clears the override`() {
        val hermes = backends.hermes(primary = true)
        backends.gateway()
        ChatBackendTarget.set(hermes.id)
        show()

        compose.onNodeWithText("My Hermes").performClick()
        compose.onNodeWithText("Primary backend").performClick()

        assertNull(ChatBackendTarget.selectedId.value)
    }

    @Test fun `a disabled backend is not offered`() {
        backends.hermes(primary = true)
        val gateway = backends.gateway()
        backends.repo.setEnabled(gateway.id, false)
        show()

        compose.onNodeWithText("Primary: My Hermes").performClick()
        compose.onNodeWithText("My OpenClaw").assertDoesNotExist()
    }
}
