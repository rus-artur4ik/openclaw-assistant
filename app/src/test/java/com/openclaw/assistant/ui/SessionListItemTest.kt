package com.openclaw.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openclaw.assistant.ChatProduct
import com.openclaw.assistant.SessionListItem
import com.openclaw.assistant.SessionUiModel
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.preferredOpenClawBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A conversation row must say which product it belongs to.
 *
 * With both branches configured the list mixes them freely, and the badge is
 * the only thing telling the user where their next message will go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class SessionListItemTest {

    @get:Rule val compose = createComposeRule()

    private fun session(product: ChatProduct, isGateway: Boolean = false) = SessionUiModel(
        id = "s1",
        title = "Yesterday's thread",
        createdAt = 1_756_000_000_000L,
        isGateway = isGateway,
        product = product,
    )

    private fun show(
        model: SessionUiModel,
        onClick: () -> Unit = {},
        onLongClick: () -> Unit = {},
    ) = compose.setContent { SessionListItem(session = model, onClick = onClick, onLongClick = onLongClick) }

    @Test fun `a hermes conversation is badged Hermes Agent`() {
        show(session(ChatProduct.HERMES))
        compose.onNodeWithText("Yesterday's thread").assertIsDisplayed()
        compose.onNodeWithText("Hermes Agent").assertIsDisplayed()
        compose.onNodeWithText("OpenClaw").assertDoesNotExist()
    }

    @Test fun `an openclaw conversation is badged OpenClaw`() {
        show(session(ChatProduct.OPENCLAW))
        compose.onNodeWithText("OpenClaw").assertIsDisplayed()
        compose.onNodeWithText("Hermes Agent").assertDoesNotExist()
    }

    @Test fun `a gateway conversation is badged OpenClaw`() {
        show(session(ChatProduct.OPENCLAW, isGateway = true))
        compose.onNodeWithText("OpenClaw").assertIsDisplayed()
    }

    @Test fun `opening a row is reported`() {
        var opened = false
        show(session(ChatProduct.HERMES), onClick = { opened = true })
        compose.onNodeWithText("Yesterday's thread").performClick()
        assertTrue(opened)
    }

    @Test fun `the overflow control is reported separately`() {
        var opened = false
        var menu = false
        show(session(ChatProduct.HERMES), onClick = { opened = true }, onLongClick = { menu = true })
        compose.onNodeWithContentDescription("Menu").performClick()
        assertTrue(menu)
        assertEquals(false, opened)
    }
}

/**
 * Which OpenClaw backend a new gateway conversation is created against.
 */
class PreferredOpenClawBackendTest {

    private fun backend(id: String, type: BackendType, primary: Boolean = false) = AgentBackendConfig(
        id = id,
        displayName = id,
        type = type,
        isPrimary = primary,
        baseUrl = "http://$id.test",
    )

    @Test fun `primary wins`() {
        val list = listOf(
            backend("api", BackendType.OPENCLAW_HTTP),
            backend("gw", BackendType.OPENCLAW_GATEWAY, primary = true),
        )
        assertEquals("gw", list.preferredOpenClawBackend()?.id)
    }

    @Test fun `a gateway is preferred over http when neither is primary`() {
        val list = listOf(
            backend("api", BackendType.OPENCLAW_HTTP),
            backend("gw", BackendType.OPENCLAW_GATEWAY),
        )
        assertEquals("gw", list.preferredOpenClawBackend()?.id)
    }

    @Test fun `the first entry is used when nothing else applies`() {
        val list = listOf(backend("api", BackendType.OPENCLAW_HTTP))
        assertEquals("api", list.preferredOpenClawBackend()?.id)
    }

    @Test fun `an empty list has no preference`() {
        assertNull(emptyList<AgentBackendConfig>().preferredOpenClawBackend())
    }
}
