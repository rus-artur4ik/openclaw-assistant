package com.openclaw.assistant.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openclaw.assistant.ChatScreen
import com.openclaw.assistant.backend.AgentEvent
import com.openclaw.assistant.ui.BackendTestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Chat screen shows for each backend branch.
 *
 * Hermes streams tokens, offers Stop and can ask for tool approval; the gateway
 * reports pending tool calls and can raise a TLS trust prompt. Rendering one
 * branch's affordances while the other is active is exactly the kind of bug
 * that survives hand-testing a single backend, so both are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ChatScreenBranchTest {

    @get:Rule(order = 0) val backends = BackendTestEnv()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(
        state: ChatUiState,
        onSend: (String) -> Unit = {},
        onStopGenerating: () -> Unit = {},
        onApprovalChoice: (String) -> Unit = {},
    ) {
        compose.setContent {
            ChatScreen(
                uiState = state,
                allSessions = emptyList(),
                currentSessionId = null,
                onSendMessage = onSend,
                onStartListening = {},
                onStopListening = {},
                onStopSpeaking = {},
                onInterruptAndListen = {},
                onBack = {},
                onStopGenerating = onStopGenerating,
                onApprovalChoice = onApprovalChoice,
            )
        }
    }

    private fun message(text: String, isUser: Boolean) = ChatMessage(text = text, isUser = isUser)

    // ---- shared transcript --------------------------------------------------

    @Test fun `both branches render the transcript the same way`() {
        show(
            ChatUiState(
                messages = listOf(message("what is the weather", true), message("cold", false)),
            ),
        )
        compose.onNodeWithText("what is the weather").assertIsDisplayed()
        compose.onNodeWithText("cold").assertIsDisplayed()
    }

    @Test fun `the thinking indicator is shown while a turn is in flight`() {
        show(ChatUiState(isThinking = true))
        compose.onNodeWithText("Thinking", substring = true).assertIsDisplayed()
    }

    // ---- hermes branch ------------------------------------------------------

    @Test fun `a streaming partial answer is rendered as a draft bubble`() {
        show(ChatUiState(streamingAssistantText = "partial answer so far"))
        compose.onNodeWithText("partial answer so far").assertIsDisplayed()
    }

    @Test fun `an empty streaming buffer does not render an empty bubble`() {
        show(ChatUiState(messages = listOf(message("hi", true)), streamingAssistantText = ""))
        compose.onNodeWithText("hi").assertIsDisplayed()
    }

    @Test fun `stop is offered only while a run is cancellable`() {
        show(ChatUiState(activeRunId = "run-1", streamingAssistantText = "half an answer"))
        compose.onNodeWithText("Stop generating").assertIsDisplayed()
    }

    @Test fun `stop is absent when the backend exposed no run`() {
        // The gateway branch never sets activeRunId; offering Stop there would be
        // a button that silently does nothing.
        show(ChatUiState(isThinking = true, activeRunId = null))
        compose.onNodeWithText("Stop generating").assertDoesNotExist()
    }

    @Test fun `tapping stop reports the request once`() {
        var stops = 0
        show(ChatUiState(activeRunId = "run-1"), onStopGenerating = { stops++ })
        compose.onNodeWithText("Stop generating").performClick()
        assertEquals(1, stops)
    }

    @Test fun `an approval request renders exactly the choices the server offered`() {
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "run-1",
                    requestId = "req-1",
                    tool = "shell",
                    command = "rm -rf build",
                    description = "Delete the build directory",
                    choices = listOf("once", "deny"),
                ),
            ),
        )
        compose.onNodeWithText("Allow this tool?").assertIsDisplayed()
        compose.onNodeWithText("Tool: shell").assertIsDisplayed()
        compose.onNodeWithText("Delete the build directory").assertIsDisplayed()
        compose.onNodeWithText("rm -rf build").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
        // The server did not offer these, so the dialog must not invent them —
        // answering with an unsupported choice leaves the run blocked.
        compose.onNodeWithText("Always allow").assertDoesNotExist()
        compose.onNodeWithText("Allow for this session").assertDoesNotExist()
    }

    @Test fun `a full choice set is rendered in full`() {
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "r", requestId = "q", tool = "web",
                    choices = listOf("once", "session", "always", "deny"),
                ),
            ),
        )
        listOf("Allow once", "Allow for this session", "Always allow", "Deny").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun `each approval choice is reported verbatim`() {
        val answered = mutableListOf<String>()
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "r", requestId = "q", tool = "web",
                    choices = listOf("once", "session", "always", "deny"),
                ),
            ),
            onApprovalChoice = { answered += it },
        )
        compose.onNodeWithText("Always allow").performClick()
        assertEquals(listOf("always"), answered)
    }

    @Test fun `denying is reported as deny`() {
        val answered = mutableListOf<String>()
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "r", requestId = "q", tool = "shell", choices = listOf("once", "deny"),
                ),
            ),
            onApprovalChoice = { answered += it },
        )
        compose.onNodeWithText("Deny").performClick()
        assertEquals(listOf("deny"), answered)
    }

    @Test fun `an approval with no tool name still renders its choices`() {
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "r", requestId = "q", tool = null, command = null,
                    description = null, choices = listOf("once", "deny"),
                ),
            ),
        )
        compose.onNodeWithText("Allow this tool?").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
    }

    @Test fun `an unknown choice falls back to its raw name rather than vanishing`() {
        show(
            ChatUiState(
                pendingApproval = AgentEvent.ApprovalRequest(
                    runId = "r", requestId = "q", tool = "x", choices = listOf("escalate", "deny"),
                ),
            ),
        )
        compose.onNodeWithText("escalate").assertIsDisplayed()
    }

    @Test fun `no approval dialog is shown when nothing is pending`() {
        show(ChatUiState(messages = listOf(message("hello", true))))
        compose.onNodeWithText("Allow this tool?").assertDoesNotExist()
    }

    // ---- gateway branch -----------------------------------------------------

    @Test fun `pending gateway tool calls are listed`() {
        show(ChatUiState(pendingToolCalls = listOf("read_file", "bash")))
        compose.onNodeWithText("Running tools").assertIsDisplayed()
        compose.onNodeWithText("read_file").assertIsDisplayed()
        compose.onNodeWithText("bash").assertIsDisplayed()
    }

    @Test fun `the gateway branch shows no hermes run controls`() {
        show(ChatUiState(isNodeChatMode = true, isThinking = true, pendingToolCalls = listOf("bash")))
        compose.onNodeWithText("Stop generating").assertDoesNotExist()
        compose.onNodeWithText("Allow this tool?").assertDoesNotExist()
    }

    // ---- shared affordances -------------------------------------------------

    @Test fun `partial speech transcription is shown for either branch`() {
        show(ChatUiState(partialText = "turn on the lights"))
        compose.onNodeWithText("turn on the lights").assertIsDisplayed()
    }

    @Test fun `the speaking indicator offers a stop control`() {
        var stopped = false
        compose.setContent {
            ChatScreen(
                uiState = ChatUiState(isSpeaking = true),
                allSessions = emptyList(),
                currentSessionId = null,
                onSendMessage = {},
                onStartListening = {},
                onStopListening = {},
                onStopSpeaking = { stopped = true },
                onInterruptAndListen = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Speaking", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertTrue(stopped)
    }

    @Test fun `attachments are listed for either branch`() {
        show(
            ChatUiState(
                attachments = listOf(
                    PendingFileAttachment(id = "1", fileName = "photo.jpg", mimeType = "image/jpeg", base64 = "AA"),
                ),
            ),
        )
        compose.onNodeWithText("photo.jpg").assertIsDisplayed()
    }
}
