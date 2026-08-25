package com.openclaw.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where this build points people at its own source, and what it no longer
 * tells them to install.
 *
 * Two separate mistakes are guarded here. This app is a fork, and its update
 * check, issue link and README badges had each drifted back to the parent
 * project — invisible from inside the app. And it used to instruct users to
 * pipe a host-side pairing helper into bash; that helper is gone, and nothing
 * shipped should mention it or its QR any more.
 */
class ProjectLinksTest {

    @Test fun `the owner is this project, spelled out rather than derived`() {
        // Written literally on purpose: asserting OWNER against itself would
        // pass no matter what it was changed to.
        assertEquals("rus-artur4ik", ProjectLinks.OWNER)
        assertEquals("openclaw-assistant", ProjectLinks.REPO)
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
        assertEquals(emptyList<String>(), scanFor(FORK_PARENT))
    }

    @Test fun `nothing shipped still tells the user to install the pairing helper`() {
        // The helper and its QR were removed in favour of instructions the app
        // states itself. A stray mention would send someone to a script that no
        // longer exists in this repository.
        assertEquals(emptyList<String>(), scanFor("agentvoice-pair"))
        assertEquals(emptyList<String>(), scanFor("integrations/agentvoice-pair"))
    }

    @Test fun `the pairing deep link is no longer advertised`() {
        // `agentvoice://pair` is the Mobile Bridge's own QR and stays; the
        // setup/import scheme that the helper drove is gone.
        assertEquals(emptyList<String>(), scanFor("agentvoice://setup"))
        assertEquals(emptyList<String>(), scanFor("agentvoice://hermes"))
    }

    /** Files under the shipped paths that still contain [needle]. */
    private fun scanFor(needle: String): List<String> {
        val root = repoRoot()
        var scanned = 0
        val hits = SCANNED_PATHS
            .map { File(root, it) }
            .onEach {
                // Never let a moved or renamed path turn this into a test that
                // silently checks nothing.
                assertTrue("expected to scan ${it.path}, but it does not exist", it.exists())
            }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension in setOf("kt", "sh", "md", "py", "xml") }
            .onEach { scanned++ }
            .filter { it.readText().contains(needle) }
            .map { it.toRelativeString(root) }
        assertTrue("scanned nothing at all — the paths must be wrong", scanned > 20)
        return hits
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
