package com.openclaw.assistant.node

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the one line that made the gateway look unreachable while it was fine.
 *
 * [NodeRuntime] needs the Android KeyStore and cannot be constructed on the JVM, so the wiring
 * itself is checked in source. It is worth guarding: the health flag is cleared on every
 * disconnect, and if the reconnect stops re-checking it, voice turns and chat sends fail with
 * "gateway not connected" until the user happens to open a chat session.
 */
class NodeRuntimeHealthWiringTest {

    @Test fun `the operator socket re-checks health when it connects`() {
        val operatorBlock = operatorSessionBlock()

        assertTrue(
            "NodeRuntime's operator onConnected no longer calls chat.onConnected(); a reconnect " +
                "would leave chatHealthOk stuck false. Block was:\n$operatorBlock",
            operatorBlock.contains("chat.onConnected()"),
        )
    }

    /**
     * The operator session declaration, up to where the node session begins. Chat rides the
     * operator socket, so the call has to sit in this block rather than anywhere in the file.
     */
    private fun operatorSessionBlock(): String {
        val source = File(repoRoot(), "app/src/main/java/com/openclaw/assistant/node/NodeRuntime.kt")
        assertTrue("missing ${source.path}", source.isFile)
        val text = source.readText()

        val start = text.indexOf("private val operatorSession")
        assertTrue("operatorSession declaration not found in ${source.path}", start >= 0)
        val end = text.indexOf("private val nodeSession", start)
        assertTrue("nodeSession declaration not found after operatorSession", end > start)

        val block = text.substring(start, end)
        // Sanity: if the parse drifted onto something tiny, the assertion above would be vacuous.
        assertTrue("operator session block looks too small to be real: ${block.length} chars", block.length > 500)
        return block
    }

    /** Unit tests run with the module directory as their working directory. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root from ${File("").absolutePath}")
    }
}
