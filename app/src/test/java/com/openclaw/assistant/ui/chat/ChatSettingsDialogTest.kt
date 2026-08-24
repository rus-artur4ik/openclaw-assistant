package com.openclaw.assistant.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.openclaw.assistant.ChatSettingsDialog
import com.openclaw.assistant.ui.BackendTestEnv
import com.openclaw.assistant.ui.backend.ChatBackendTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The in-chat backend switcher — the control that decides which branch the next
 * message takes.
 *
 * It also hosts per-branch model controls: only Hermes can be asked for a model
 * catalogue, and only Hermes needs a provider stored alongside the model.
 */
@RunWith(RobolectricTestRunner::class)
// A tall viewport keeps the scrollable dialog body on screen, so
// assertions read as "the user can see this", not "it exists somewhere".
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class ChatSettingsDialogTest {

    @get:Rule(order = 0) val backends = BackendTestEnv()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(state: ChatUiState = ChatUiState(), onDismiss: () -> Unit = {}) {
        compose.setContent {
            ChatSettingsDialog(uiState = state, onDismiss = onDismiss, onAgentSelected = {})
        }
    }

    @Test fun `every enabled backend is offered as a target`() {
        backends.hermes(primary = true)
        backends.gateway()
        backends.openClawHttp()
        show()

        compose.onNodeWithText("Use primary backend").assertIsDisplayed()
        // The primary row repeats the primary backend's name as its subtitle, so
        // the Hermes name legitimately appears twice.
        compose.onAllNodesWithText("My Hermes").assertCountEquals(2)
        compose.onNodeWithText("My OpenClaw").assertIsDisplayed()
        compose.onNodeWithText("My OpenClaw API").assertIsDisplayed()
    }

    @Test fun `each backend is labelled with its kind`() {
        backends.hermes(primary = true)
        backends.gateway()
        backends.openClawHttp()
        show()

        // "Hermes Agent" also names the backend kind; the three subtitles must
        // all be present so the two OpenClaw kinds are not confusable.
        compose.onNodeWithText("OpenClaw", substring = false).assertIsDisplayed()
        compose.onNodeWithText("OpenClaw API", substring = false).assertIsDisplayed()
    }

    @Test fun `a disabled backend is not offered`() {
        val hermes = backends.hermes(primary = true)
        val gateway = backends.gateway()
        backends.repo.setEnabled(gateway.id, false)
        show()

        compose.onAllNodesWithText("My Hermes").assertCountEquals(2)
        compose.onNodeWithText("My OpenClaw").assertDoesNotExist()
        assertEquals(hermes.id, backends.repo.primary?.id)
    }

    @Test fun `selecting a gateway target and saving switches the branch`() {
        backends.hermes(primary = true)
        val gateway = backends.gateway()
        show()

        compose.onNodeWithText("My OpenClaw").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals(gateway.id, ChatBackendTarget.selectedId.value)
    }

    @Test fun `selecting a hermes target and saving switches the branch back`() {
        val hermes = backends.hermes()
        backends.gateway(primary = true)
        ChatBackendTarget.set(null)
        show()

        compose.onNodeWithText("My Hermes").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals(hermes.id, ChatBackendTarget.selectedId.value)
    }

    @Test fun `choosing the primary row clears the override`() {
        val hermes = backends.hermes(primary = true)
        backends.gateway()
        ChatBackendTarget.set(hermes.id)
        show()

        compose.onNodeWithText("Use primary backend").performClick()
        compose.onNodeWithText("Save").performClick()

        assertNull(ChatBackendTarget.selectedId.value)
    }

    @Test fun `cancel leaves the selection untouched`() {
        backends.hermes(primary = true)
        backends.gateway()
        show()

        compose.onNodeWithText("My OpenClaw").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertNull(ChatBackendTarget.selectedId.value)
    }

    @Test fun `hermes exposes the model catalogue controls`() {
        backends.hermes(primary = true)
        show()

        // The dialog body scrolls, so reach the control the way a user would.
        compose.onNodeWithText("Hermes Model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Load Models").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply to Hermes").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the gateway does not expose hermes-only model controls`() {
        // Those buttons talk to Hermes endpoints; showing them for a gateway
        // target produces a request the host cannot answer.
        backends.gateway(primary = true)
        show()

        compose.onNodeWithText("Load Models").assertDoesNotExist()
        compose.onNodeWithText("Apply to Hermes").assertDoesNotExist()
        compose.onNodeWithText("Hermes Model").assertDoesNotExist()
        compose.onNodeWithText("OpenClaw Model").performScrollTo().assertIsDisplayed()
    }

    @Test fun `openclaw http does not expose hermes-only model controls`() {
        backends.openClawHttp(primary = true)
        show()

        compose.onNodeWithText("Load Models").assertDoesNotExist()
        compose.onNodeWithText("Apply to Hermes").assertDoesNotExist()
        compose.onNodeWithText("Hermes Model").assertDoesNotExist()
    }

    @Test fun `saving persists the model onto the selected backend`() {
        val gateway = backends.gateway(primary = true, model = "openclaw")
        show()

        compose.onNodeWithText("Save").performClick()

        assertEquals("openclaw", backends.repo.backends.value.first { it.id == gateway.id }.modelName)
    }

    @Test fun `a hermes backend with no model saved keeps the server-default sentinel`() {
        val hermes = backends.hermes(primary = true, model = null)
        show()

        compose.onNodeWithText("Save").performClick()

        // "default" is the placeholder the client strips before sending, so the
        // server picks its own model rather than being handed a bogus name.
        assertEquals("default", backends.repo.backends.value.first { it.id == hermes.id }.modelName)
    }

    @Test fun `with no backends configured the dialog still renders`() {
        show()
        compose.onNodeWithText("Use primary backend").assertIsDisplayed()
        compose.onNodeWithText("Load Models").assertDoesNotExist()
        compose.onNodeWithText("Hermes Model").assertDoesNotExist()
    }

    @Test fun `the openclaw agent picker is hidden while the gateway owns the chat`() {
        backends.gateway(primary = true)
        show(ChatUiState(isNodeChatMode = true, availableAgents = emptyList()))
        compose.onNodeWithText("OpenClaw agent").assertDoesNotExist()
    }
}
