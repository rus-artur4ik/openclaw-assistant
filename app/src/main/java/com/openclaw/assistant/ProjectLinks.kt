package com.openclaw.assistant

/**
 * Where this build points people at its own source.
 *
 * Kept in one place because the setup screens show the install command *and*
 * separately shorten it for display: when those two were written out
 * independently, changing the URL silently stopped the shortening from
 * matching and the full line was rendered instead.
 *
 * The host-side helper this installs has to match the app that reads its QR,
 * so these must name the repository this APK was built from — not the upstream
 * it was forked from, whose helper knows nothing about these changes.
 */
object ProjectLinks {
    const val OWNER = "rus-artur4ik"
    const val REPO = "openclaw-assistant"
    const val BRANCH = "main"

    /** Base for files served straight out of the repository. */
    const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/"

    /** One-liner the setup screens tell the user to paste on their computer. */
    const val PAIRING_INSTALL_COMMAND =
        "curl -fsSL ${RAW_BASE}integrations/agentvoice-pair/install.sh | bash"

    /** Elided form of [RAW_BASE], so a long URL does not overflow the card. */
    const val RAW_BASE_ELIDED = "https://raw.githubusercontent.com/.../"
}
