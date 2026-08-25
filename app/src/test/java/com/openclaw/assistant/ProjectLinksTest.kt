package com.openclaw.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup screens tell people to pipe a URL from this repository into bash.
 *
 * Two things have gone wrong here before and both are silent: the command
 * pointed at the upstream project this one was forked from, so users installed
 * a helper that knew nothing about this app; and the elision used for display
 * was written out separately from the command, so changing one left the other
 * no longer matching and the full URL was rendered.
 */
class ProjectLinksTest {

    @Test fun `the install command points at this repository`() {
        assertTrue(
            "install command must not point at a fork parent: ${ProjectLinks.PAIRING_INSTALL_COMMAND}",
            ProjectLinks.PAIRING_INSTALL_COMMAND.contains("/${ProjectLinks.OWNER}/${ProjectLinks.REPO}/"),
        )
    }

    @Test fun `the elision actually matches the command`() {
        // If RAW_BASE ever stops being a prefix of the command, the setup card
        // silently renders the whole unshortened URL.
        assertTrue(
            "RAW_BASE is not a substring of the command",
            ProjectLinks.PAIRING_INSTALL_COMMAND.contains(ProjectLinks.RAW_BASE),
        )
        val displayed = ProjectLinks.PAIRING_INSTALL_COMMAND
            .replace(ProjectLinks.RAW_BASE, ProjectLinks.RAW_BASE_ELIDED)
        assertEquals(
            "curl -fsSL https://raw.githubusercontent.com/.../integrations/agentvoice-pair/install.sh | bash",
            displayed,
        )
    }

    @Test fun `the command fetches the installer, not the helper itself`() {
        assertTrue(ProjectLinks.PAIRING_INSTALL_COMMAND.endsWith("integrations/agentvoice-pair/install.sh | bash"))
    }

    @Test fun `no source file still points the installer at another owner`() {
        // Guards the whole set at once: the shell script and the integration
        // README carry the same URL and are not covered by the constant.
        val offenders = sequenceOf("app/src/main", "integrations")
            .map { java.io.File(it) }
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension in setOf("kt", "sh", "md", "py") }
            .filter { file ->
                file.readText().let { text ->
                    text.contains("raw.githubusercontent.com") &&
                        !text.contains("raw.githubusercontent.com/${ProjectLinks.OWNER}/") &&
                        !text.contains(ProjectLinks.RAW_BASE_ELIDED)
                }
            }
            .map { it.path }
            .toList()
        assertEquals("these still fetch the installer from another owner: $offenders", emptyList<String>(), offenders)
    }
}
