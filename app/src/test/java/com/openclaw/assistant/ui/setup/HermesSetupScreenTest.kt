package com.openclaw.assistant.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.openclaw.assistant.backend.HermesCapabilities
import com.openclaw.assistant.backend.HermesLanScanner
import com.openclaw.assistant.backend.HermesModelOption
import com.openclaw.assistant.backend.HermesSetupProbe
import com.openclaw.assistant.backend.HermesSuggestedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guided Hermes flow, state by state.
 *
 * The whole point of the wizard is that it explains what went wrong, so every
 * failure the probe can report is rendered here — the diagnosis is the feature,
 * and a wrong one sends people to check their Wi-Fi over a mistyped key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class HermesSetupScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun show(state: HermesSetupUiState, actions: HermesSetupActions = HermesSetupActions()) {
        compose.setContent { HermesSetupScreen(state = state, actions = actions) }
    }

    private fun readyState(baseUrl: String = "http://192.168.1.50:8642") = HermesSetupUiState(
        address = "192.168.1.50",
        probe = HermesSetupProbe.Ready(baseUrl, HermesCapabilities(detected = true)),
        capabilities = HermesCapabilities(detected = true),
    )

    // ---- step 1: find -------------------------------------------------------

    @Test fun `the first step asks only for an address and a key`() {
        show(HermesSetupUiState())
        compose.onNodeWithText("Find your server").assertIsDisplayed()
        compose.onNodeWithText("Address").assertIsDisplayed()
        compose.onNodeWithText("API key").assertIsDisplayed()
        compose.onNodeWithText("Search local network").assertIsDisplayed()
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
    }

    @Test fun `connect is disabled until an address is typed`() {
        show(HermesSetupUiState())
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test fun `connect is enabled once there is an address`() {
        show(HermesSetupUiState(address = "192.168.1.50"))
        compose.onNodeWithText("Connect").assertIsEnabled()
    }

    @Test fun `connect is reported`() {
        var connected = false
        show(HermesSetupUiState(address = "h"), HermesSetupActions(onConnect = { connected = true }))
        compose.onNodeWithText("Connect").performClick()
        assertTrue(connected)
    }

    @Test fun `a probe in flight is visible`() {
        show(HermesSetupUiState(address = "h", busy = true))
        compose.onNodeWithText("Checking…").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    // ---- step 1: what the probe found --------------------------------------

    @Test fun `a working server is confirmed and offers to continue`() {
        show(readyState())
        compose.onNodeWithText("Connected to http://192.168.1.50:8642").assertIsDisplayed()
        compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    @Test fun `a server that needs a key says so rather than reporting failure`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.50",
                probe = HermesSetupProbe.NeedsKey("http://192.168.1.50:8642"),
            ),
        )
        compose.onNodeWithText("Found Hermes at http://192.168.1.50:8642. It needs an API key.")
            .assertIsDisplayed()
        // Nothing to continue to yet.
        compose.onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test fun `a rejected key is not reported as a network problem`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.50",
                apiKey = "wrong",
                probe = HermesSetupProbe.KeyRejected("http://192.168.1.50:8642", "HTTP 401"),
            ),
        )
        compose.onNodeWithText("http://192.168.1.50:8642 answered, but rejected that key.")
            .assertIsDisplayed()
    }

    @Test fun `something that is not hermes is named as such`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.1",
                probe = HermesSetupProbe.NotHermes("http://192.168.1.1:8642", "HTTP 404"),
            ),
        )
        compose.onNodeWithText(
            "http://192.168.1.1:8642 answered, but it does not serve the Hermes API (HTTP 404).",
        ).assertIsDisplayed()
    }

    @Test fun `an unreachable address lists what was tried`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.50",
                probe = HermesSetupProbe.Unreachable(
                    listOf("http://192.168.1.50:8642", "https://192.168.1.50:8642"),
                    "timeout",
                ),
            ),
        )
        compose.onNodeWithText(
            "Nothing answered. Tried http://192.168.1.50:8642, https://192.168.1.50:8642",
        ).assertIsDisplayed()
    }

    // ---- step 1: local network search --------------------------------------

    @Test fun `a running scan shows progress and can be stopped`() {
        var stopped = false
        show(
            HermesSetupUiState(scanning = true, scanScanned = 40, scanTotal = 253),
            HermesSetupActions(onStopScan = { stopped = true }),
        )
        compose.onNodeWithText("Searching… 40 of 253").assertIsDisplayed()
        compose.onNodeWithText("Stop searching").performClick()
        assertTrue(stopped)
    }

    @Test fun `found servers are listed with the model they advertise`() {
        show(
            HermesSetupUiState(
                scanFinished = true,
                scanResults = listOf(
                    HermesLanScanner.Found("http://192.168.1.50:8642", "192.168.1.50", "hermes-agent"),
                ),
            ),
        )
        compose.onNodeWithText("Found on this network").assertIsDisplayed()
        compose.onNodeWithText("192.168.1.50").assertIsDisplayed()
        compose.onNodeWithText("hermes-agent").assertIsDisplayed()
    }

    @Test fun `a found server that wants a key is labelled`() {
        show(
            HermesSetupUiState(
                scanFinished = true,
                scanResults = listOf(
                    HermesLanScanner.Found("http://192.168.1.51:8642", "192.168.1.51", requiresKey = true),
                ),
            ),
        )
        compose.onNodeWithText("needs a key").assertIsDisplayed()
    }

    @Test fun `picking a found server is reported`() {
        val found = HermesLanScanner.Found("http://192.168.1.50:8642", "192.168.1.50", "hermes-agent")
        val picked = mutableListOf<HermesLanScanner.Found>()
        show(
            HermesSetupUiState(scanFinished = true, scanResults = listOf(found)),
            HermesSetupActions(onPickFound = { picked += it }),
        )
        compose.onNodeWithText("192.168.1.50").performClick()
        assertEquals(listOf(found), picked)
    }

    @Test fun `an empty scan explains the most likely cause`() {
        // Hermes binds to loopback by default; that is the answer nine times
        // out of ten, and the screen should say so instead of "not found".
        show(HermesSetupUiState(scanFinished = true, scanResults = emptyList()))
        compose.onNodeWithText(
            "Nothing answered on this network. Hermes listens on 127.0.0.1 by default — bind it to your LAN, or type the address in.",
        ).assertIsDisplayed()
    }

    @Test fun `with no subnet the search is disabled and explained`() {
        show(HermesSetupUiState(scanUnavailable = true))
        compose.onNodeWithText("Search local network").assertIsNotEnabled()
        compose.onNodeWithText("Join a Wi-Fi network to search, or type the address in.").assertIsDisplayed()
    }

    @Test fun `the manual editor is always one tap away`() {
        var manual = false
        show(HermesSetupUiState(), HermesSetupActions(onManualEntry = { manual = true }))
        compose.onNodeWithText("Enter details manually").performScrollTo().performClick()
        assertTrue(manual)
    }

    // ---- step 1: telling the user what to actually do ----------------------

    @Test fun `the host instructions are available before anything has failed`() {
        show(HermesSetupUiState())
        compose.onNodeWithText("Hermes not showing up?").performScrollTo().assertIsDisplayed()
        // Collapsed until asked for, so it does not bury the address field.
        compose.onNodeWithText("agentvoice-pair").assertDoesNotExist()
    }

    @Test fun `expanding the instructions shows both the helper and the manual steps`() {
        show(HermesSetupUiState())
        compose.onNodeWithText("Show me what to do").performScrollTo().performClick()

        compose.onNodeWithText("agentvoice-pair").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("hermes gateway run --replace").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1. In ~/.hermes/config.yaml change host: 127.0.0.1 to host: 0.0.0.0")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2. In ~/.hermes/.env set API_SERVER_KEY to a value of your choosing")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `an empty scan opens the instructions without being asked`() {
        // Binding to 127.0.0.1 is the usual cause, and it cannot be fixed from
        // this screen — so the steps appear rather than hiding behind a tap.
        show(HermesSetupUiState(scanFinished = true, scanResults = emptyList()))
        compose.onNodeWithText("agentvoice-pair").performScrollTo().assertIsDisplayed()
    }

    @Test fun `an unreachable address opens the instructions too`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.50",
                probe = HermesSetupProbe.Unreachable(listOf("http://192.168.1.50:8642"), "timeout"),
            ),
        )
        compose.onNodeWithText("agentvoice-pair").performScrollTo().assertIsDisplayed()
    }

    @Test fun `a working connection does not nag with setup instructions`() {
        show(readyState())
        compose.onNodeWithText("agentvoice-pair").assertDoesNotExist()
    }

    @Test fun `a missing key says where to find it`() {
        show(
            HermesSetupUiState(
                address = "192.168.1.50",
                probe = HermesSetupProbe.NeedsKey("http://192.168.1.50:8642"),
            ),
        )
        compose.onNodeWithText(
            "Open ~/.hermes/.env on that computer and copy the value of API_SERVER_KEY into the field above.",
        ).performScrollTo().assertIsDisplayed()
        // The fix is one field up, so the host steps stay collapsed.
        compose.onNodeWithText("agentvoice-pair").assertDoesNotExist()
    }

    @Test fun `a rejected key points at the value to compare`() {
        show(
            HermesSetupUiState(
                apiKey = "wrong",
                probe = HermesSetupProbe.KeyRejected("http://192.168.1.50:8642", "HTTP 401"),
            ),
        )
        compose.onNodeWithText(
            "Compare the key with API_SERVER_KEY in ~/.hermes/.env — it is the whole value, without surrounding quotes.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun `something that is not hermes suggests checking the port`() {
        show(
            HermesSetupUiState(
                probe = HermesSetupProbe.NotHermes("http://192.168.1.1:8642", "HTTP 404"),
            ),
        )
        compose.onNodeWithText(
            "Something else is answering there. Check the port — the Hermes API server uses 8642 unless it was moved.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun `an unreachable address lists the usual causes in order`() {
        show(
            HermesSetupUiState(
                probe = HermesSetupProbe.Unreachable(listOf("http://192.168.1.50:8642"), "timeout"),
            ),
        )
        compose.onNodeWithText(
            "Most likely, in order: Hermes is still bound to 127.0.0.1; the phone is on a different network; or a firewall is blocking port 8642.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun `a successful probe offers no fix-it line`() {
        show(readyState())
        compose.onNodeWithText("Most likely, in order", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Open ~/.hermes/.env", substring = true).assertDoesNotExist()
    }

    // ---- step 2: review -----------------------------------------------------

    private fun reviewState(caps: HermesCapabilities) = readyState().copy(
        step = HermesSetupStep.REVIEW,
        capabilities = caps,
    )

    @Test fun `a full-featured server lists what it can do`() {
        show(
            reviewState(
                HermesCapabilities(
                    detected = true,
                    sessionChatStreaming = true,
                    runSubmission = true,
                    runEventsSse = true,
                    runStop = true,
                    approvalEvents = true,
                ),
            ),
        )
        compose.onNodeWithText("This server supports").assertIsDisplayed()
        compose.onNodeWithText("Server-side history").assertIsDisplayed()
        compose.onNodeWithText("Stopping a running turn").assertIsDisplayed()
        compose.onNodeWithText("Tool approval prompts").assertIsDisplayed()
    }

    @Test fun `a server without approvals says so rather than staying silent`() {
        show(reviewState(HermesCapabilities(detected = true, sessionChatStreaming = true)))
        compose.onNodeWithText("No tool approval prompts").assertIsDisplayed()
        compose.onNodeWithText("Tool approval prompts").assertDoesNotExist()
    }

    @Test fun `the review explains that the transport was detected, not chosen`() {
        show(reviewState(HermesCapabilities(detected = true, sessionChatStreaming = true)))
        compose.onNodeWithText(
            "Detected, not chosen — the app asked the server what it supports. You can override the transport later in the backend editor.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun `the chosen transport is named`() {
        show(reviewState(HermesCapabilities(detected = true, sessionChatStreaming = true)))
        compose.onNodeWithText("Chosen transport: Server-side sessions").assertIsDisplayed()
    }

    @Test fun `a runs-only server reports the runs transport`() {
        show(reviewState(HermesCapabilities(detected = true, runSubmission = true, runEventsSse = true)))
        compose.onNodeWithText("Chosen transport: Runs API").assertIsDisplayed()
    }

    @Test fun `an older server that answered no probe is called out`() {
        show(reviewState(HermesCapabilities.LEGACY))
        compose.onNodeWithText(
            "This build did not answer the capability probe, so only chat completions is assumed.",
        ).assertIsDisplayed()
    }

    @Test fun `the model and provider come prefilled from the server`() {
        show(
            reviewState(HermesCapabilities(detected = true)).copy(
                model = "kimi-k2",
                provider = "moonshot",
                suggested = HermesSuggestedModel(
                    model = "kimi-k2",
                    provider = "moonshot",
                    options = listOf(HermesModelOption("kimi-k2", provider = "moonshot")),
                ),
            ),
        )
        // Once in the model field, once as the chip offering the same choice.
        compose.onAllNodesWithText("kimi-k2").assertCountEquals(2)
        compose.onNodeWithText("moonshot").assertIsDisplayed()
    }

    @Test fun `picking a suggested model carries its provider along`() {
        // Without the provider Hermes ignores the model, so the chip has to set
        // both or it silently does nothing.
        val models = mutableListOf<String>()
        val providers = mutableListOf<String>()
        show(
            reviewState(HermesCapabilities(detected = true)).copy(
                suggested = HermesSuggestedModel(
                    model = null,
                    provider = null,
                    options = listOf(HermesModelOption("gpt-5", provider = "openai")),
                ),
            ),
            HermesSetupActions(
                onModelChange = { models += it },
                onProviderChange = { providers += it },
            ),
        )
        compose.onNodeWithText("gpt-5").performScrollTo().performClick()
        assertEquals(listOf("gpt-5"), models)
        assertEquals(listOf("openai"), providers)
    }

    // ---- step 3: finish -----------------------------------------------------

    @Test fun `the last step asks for a name and whether to make it primary`() {
        show(readyState().copy(step = HermesSetupStep.FINISH, displayName = "Home Hermes"))
        compose.onNodeWithText("Name it").assertIsDisplayed()
        compose.onNodeWithText("Home Hermes").assertIsDisplayed()
        compose.onNodeWithText("Use as Primary backend").assertIsDisplayed()
        compose.onNodeWithText("Save backend").assertIsEnabled()
    }

    @Test fun `the last step explains what Primary actually changes`() {
        show(readyState().copy(step = HermesSetupStep.FINISH, displayName = "Home Hermes"))
        compose.onNodeWithText(
            "Primary is where the wake word, Voice Overlay and Wear OS send their turns. Chat can still be pointed at any backend per conversation.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun `saving is refused without a name`() {
        show(readyState().copy(step = HermesSetupStep.FINISH, displayName = ""))
        compose.onNodeWithText("Save backend").assertIsNotEnabled()
    }

    @Test fun `saving is refused when nothing was reached`() {
        show(HermesSetupUiState(step = HermesSetupStep.FINISH, displayName = "Home Hermes"))
        compose.onNodeWithText("Save backend").assertIsNotEnabled()
    }

    @Test fun `saving is reported`() {
        var saved = false
        show(
            readyState().copy(step = HermesSetupStep.FINISH, displayName = "Home Hermes"),
            HermesSetupActions(onSave = { saved = true }),
        )
        compose.onNodeWithText("Save backend").performClick()
        assertTrue(saved)
    }

    @Test fun `going back is offered from every step after the first`() {
        var backs = 0
        val actions = HermesSetupActions(onBack = { backs++ })
        show(readyState().copy(step = HermesSetupStep.REVIEW), actions)
        compose.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(1, backs)
    }
}
