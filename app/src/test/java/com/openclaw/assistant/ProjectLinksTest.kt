package com.openclaw.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where this build points people at its own source.
 *
 * This app is a fork, and four separate links had drifted back to the parent
 * project: the command the setup screen tells you to pipe into bash, the
 * update check, the "report an issue" button, and the README badges. Every one
 * of them is invisible from inside the app — you only find out when a user
 * installs the wrong helper or files a bug in someone else's tracker.
 */
class ProjectLinksTest {

    @Test fun `the owner is this project, spelled out rather than derived`() {
        // Written literally on purpose: asserting OWNER against itself would
        // pass no matter what it was changed to.
        assertEquals("rus-artur4ik", ProjectLinks.OWNER)
        assertEquals("openclaw-assistant", ProjectLinks.REPO)
    }

    @Test fun `the install command points at this repository`() {
        assertTrue(
            "install command must not point at the fork parent: ${ProjectLinks.PAIRING_INSTALL_COMMAND}",
            ProjectLinks.PAIRING_INSTALL_COMMAND
                .contains("https://raw.githubusercontent.com/rus-artur4ik/openclaw-assistant/main/"),
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

    @Test fun `the update check asks this repository for releases`() {
        // A fork that offers its users the parent project's APK is offering
        // them a different app.
        assertEquals(
            "https://api.github.com/repos/rus-artur4ik/openclaw-assistant/releases/latest",
            ProjectLinks.RELEASES_API_URL,
        )
    }

    @Test fun `a bug report lands on this repository`() {
        assertEquals("https://github.com/rus-artur4ik/openclaw-assistant/issues/new", ProjectLinks.NEW_ISSUE_URL)
    }

    @Test fun `nothing shipped still references the project this was forked from`() {
        val root = repoRoot()
        val scanned = mutableListOf<File>()
        val offenders = SCANNED_PATHS
            .map { File(root, it) }
            .onEach {
                // Never let a moved or renamed path turn this into a test that
                // silently checks nothing.
                assertTrue("expected to scan ${it.path}, but it does not exist", it.exists())
            }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension in setOf("kt", "sh", "md", "py", "xml") }
            .onEach { scanned += it }
            .filter { it.readText().contains(FORK_PARENT) }
            .map { it.toRelativeString(root) }

        assertTrue("scanned nothing at all — the paths must be wrong", scanned.size > 20)
        assertEquals("these still reference $FORK_PARENT: $offenders", emptyList<String>(), offenders)
    }

    /**
     * Unit tests run with the module directory as their working directory, so
     * repo-relative paths have to be resolved rather than assumed.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root from ${File("").absolutePath}")
    }

    private companion object {
        const val FORK_PARENT = "yuga-hashimoto"
        val SCANNED_PATHS = listOf("app/src/main", "wear/src/main", "integrations", "README.md")
    }
}
