package com.openclaw.assistant

/**
 * Where this build points people at its own source.
 *
 * This app is a fork, and each of these links had independently drifted back to
 * the parent project — invisibly, because nothing inside the app shows which
 * repository it is talking to.
 */
object ProjectLinks {
    const val OWNER = "rus-artur4ik"
    const val REPO = "openclaw-assistant"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"

    /**
     * Releases of *this* build. A fork that offers its users the parent
     * project's APK is offering them a different app.
     */
    const val RELEASES_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Where a bug report about this build should land. */
    const val NEW_ISSUE_URL = "$REPO_URL/issues/new"

    /**
     * Host-side setup instructions, published as a gist.
     *
     * The commands have to run on the computer that hosts Hermes, so what the
     * phone is really for here is carrying the link across. A gist also means
     * the instructions can be corrected without shipping an APK.
     */
    private const val SETUP_GIST_ID = "a1edddd8bcbc2885831b0b378d72ba95"
    const val SETUP_GIST_URL = "https://gist.github.com/$OWNER/$SETUP_GIST_ID"
}
