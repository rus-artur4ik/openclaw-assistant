package com.openclaw.assistant.ui.backend

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.HermesTransportPreference
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
 * The backend editor renders a different form per backend type.
 *
 * Hermes needs a URL set, a provider, a memory scope and a transport; the
 * gateway needs host/port/TLS and none of those. A field leaking across
 * branches either writes meaningless config or hides one the branch requires,
 * and neither shows up while hand-testing a single backend type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class BackendEditorScreenTest {

    @get:Rule(order = 0) val backends = BackendTestEnv()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(existingId: String? = null, onDone: () -> Unit = {}) {
        compose.setContent { BackendEditorScreen(existingId = existingId, onDone = onDone) }
    }

    private fun selectType(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    // ---- type switch --------------------------------------------------------

    @Test fun `all three backend types are offered`() {
        show()
        // "Hermes Agent" is both the type chip and the default display name.
        compose.onAllNodesWithText("Hermes Agent").assertCountEquals(2)
        compose.onNodeWithText("OpenClaw").assertIsDisplayed()
        compose.onNodeWithText("OpenClaw API").assertIsDisplayed()
    }

    @Test fun `a new backend defaults to hermes`() {
        show()
        compose.onNodeWithText("Primary URL (e.g. http://host:8642)").assertIsDisplayed()
    }

    // ---- hermes form --------------------------------------------------------

    @Test fun `the hermes form exposes every hermes-only field`() {
        show()
        listOf(
            "Primary URL (e.g. http://host:8642)",
            "LAN URL (optional)",
            "Tailscale URL (optional)",
            "Public URL (optional)",
            "API key",
            "Hermes Agent target",
            "Provider (optional)",
            "Long-term memory scope (optional)",
            "Conversation transport",
            "Stream responses",
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun `the hermes form offers every transport`() {
        show()
        listOf("Automatic (recommended)", "Server-side sessions", "Runs API", "Chat completions")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun `the hermes form has no gateway fields`() {
        show()
        compose.onNodeWithText("Host").assertDoesNotExist()
        compose.onNodeWithText("Port").assertDoesNotExist()
        compose.onNodeWithText("Use TLS").assertDoesNotExist()
        compose.onNodeWithText("OpenClaw token").assertDoesNotExist()
    }

    // ---- gateway form -------------------------------------------------------

    @Test fun `the gateway form exposes host port token and tls`() {
        show()
        selectType("OpenClaw")
        listOf("Host", "Port", "OpenClaw token", "Use TLS")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun `the gateway form hides every hermes-only control`() {
        // These write Hermes request fields; on a gateway they are dead config
        // that silently does nothing.
        show()
        selectType("OpenClaw")
        listOf(
            "Conversation transport",
            "Automatic (recommended)",
            "Runs API",
            "Provider (optional)",
            "Long-term memory scope (optional)",
            "LAN URL (optional)",
            "Inspect Agent Context",
            "Load Models",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    // ---- openclaw http form -------------------------------------------------

    @Test fun `the openclaw http form asks only for a base url and token`() {
        show()
        selectType("OpenClaw API")
        compose.onNodeWithText("Base URL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Auth Token (optional)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Host").assertDoesNotExist()
        compose.onNodeWithText("Conversation transport").assertDoesNotExist()
    }

    // ---- persistence --------------------------------------------------------

    @Test fun `saving a hermes backend stores its url token and transport`() {
        var done = false
        show(onDone = { done = true })

        compose.onNodeWithText("Primary URL (e.g. http://host:8642)").performScrollTo()
            .performTextInput("http://hermes.local:8642")
        compose.onNodeWithText("API key").performScrollTo().performTextInput("secret-key")
        compose.onNodeWithText("Runs API").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()

        val saved = backends.repo.backends.value.single()
        assertEquals(BackendType.HERMES_API_SERVER, saved.type)
        assertEquals("http://hermes.local:8642", saved.baseUrl)
        assertEquals("secret-key", saved.apiKeyOrToken)
        assertEquals(HermesTransportPreference.RUNS, saved.transport)
        assertEquals(HermesTransportPreference.RUNS, saved.effectiveTransport)
        assertTrue(done)
    }

    @Test fun `saving a hermes backend stores provider and memory scope`() {
        show()
        compose.onNodeWithText("Primary URL (e.g. http://host:8642)").performScrollTo()
            .performTextInput("http://hermes.local:8642")
        compose.onNodeWithText("Provider (optional)").performScrollTo().performTextInput("openrouter")
        compose.onNodeWithText("Long-term memory scope (optional)").performScrollTo().performTextInput("artur")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        val saved = backends.repo.backends.value.single()
        assertEquals("openrouter", saved.providerName)
        assertEquals("artur", saved.memoryScopeKey)
    }

    @Test fun `saving a gateway backend stores host port and tls, not hermes fields`() {
        show()
        selectType("OpenClaw")
        compose.onNodeWithText("Host").performScrollTo().performTextInput("gw.local")
        compose.onNodeWithText("Port").performScrollTo().performTextInput("8443")
        compose.onNodeWithText("OpenClaw token").performScrollTo().performTextInput("tok")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        val saved = backends.repo.backends.value.single()
        assertEquals(BackendType.OPENCLAW_GATEWAY, saved.type)
        assertEquals("gw.local", saved.host)
        assertEquals(8443, saved.port)
        assertEquals("tok", saved.apiKeyOrToken)
        assertNull(saved.providerName)
        assertNull(saved.memoryScopeKey)
    }

    @Test fun `the port field rejects non-digits`() {
        show()
        selectType("OpenClaw")
        compose.onNodeWithText("Port").performScrollTo().performTextInput("84a4b3")
        compose.onNodeWithText("Host").performScrollTo().performTextInput("gw.local")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals(8443, backends.repo.backends.value.single().port)
    }

    @Test fun `the first backend saved becomes primary`() {
        show()
        compose.onNodeWithText("Primary URL (e.g. http://host:8642)").performScrollTo()
            .performTextInput("http://hermes.local:8642")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        assertTrue(backends.repo.backends.value.single().isPrimary)
    }

    @Test fun `a second backend does not steal primary unless asked`() {
        backends.hermes(primary = true)
        show()
        selectType("OpenClaw")
        compose.onNodeWithText("Host").performScrollTo().performTextInput("gw.local")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals("hermes-1", backends.repo.primary?.id)
    }

    @Test fun `marking primary moves it across branches`() {
        val hermes = backends.hermes(primary = true)
        show()
        selectType("OpenClaw")
        compose.onNodeWithText("Host").performScrollTo().performTextInput("gw.local")
        // The label is decoration; the checkbox beside it is the control, and it
        // is the last checkbox on the form.
        compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()

        val primary = backends.repo.primary
        assertEquals(BackendType.OPENCLAW_GATEWAY, primary?.type)
        assertEquals(false, backends.repo.backends.value.first { it.id == hermes.id }.isPrimary)
    }

    // ---- editing an existing backend ---------------------------------------

    @Test fun `editing a hermes backend prefills its fields`() {
        val hermes = backends.repo.backends.value.let {
            backends.hermes(name = "Home Hermes", primary = true, model = "kimi-k2")
        }
        show(existingId = hermes.id)

        compose.onNodeWithText("Home Hermes").assertIsDisplayed()
        compose.onNodeWithText("kimi-k2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("http://hermes.test:8642").performScrollTo().assertIsDisplayed()
    }

    @Test fun `editing a gateway backend shows the gateway form`() {
        val gateway = backends.gateway(name = "Home OpenClaw", primary = true)
        show(existingId = gateway.id)

        compose.onNodeWithText("Home OpenClaw").assertIsDisplayed()
        compose.onNodeWithText("gateway.test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8443").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Conversation transport").assertDoesNotExist()
    }

    @Test fun `editing keeps the same backend rather than adding one`() {
        val hermes = backends.hermes(primary = true)
        show(existingId = hermes.id)

        compose.onNodeWithText("Display name").performScrollTo().performTextClearance()
        compose.onNodeWithText("Display name").performScrollTo().performTextInput("Renamed")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals(1, backends.repo.backends.value.size)
        assertEquals("Renamed", backends.repo.backends.value.single().displayName)
    }

    @Test fun `a legacy backend that only set the runs flag opens on automatic`() {
        // useRunsApi predates the transport picker; those installs are migrated
        // rather than pinned to the transport that flag happened to imply.
        val legacy = backends.hermes(primary = true).copy(transport = null, useRunsApi = true)
        backends.repo.upsert(legacy)
        show(existingId = legacy.id)

        compose.onNodeWithText("Automatic (recommended)").performScrollTo().assertIsSelected()
    }

    @Test fun `a legacy backend that turned the runs flag off opens on chat completions`() {
        val legacy = backends.hermes(primary = true).copy(transport = null, useRunsApi = false)
        backends.repo.upsert(legacy)
        show(existingId = legacy.id)

        compose.onNodeWithText("Chat completions").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Automatic (recommended)").performScrollTo().assertIsNotSelected()
    }
}
