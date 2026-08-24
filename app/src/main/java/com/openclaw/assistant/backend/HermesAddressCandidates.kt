package com.openclaw.assistant.backend

import java.net.URI

/**
 * Turns whatever a person types into the base URLs worth trying.
 *
 * Nobody remembers that Hermes listens on 8642, and whether a given box speaks
 * plain HTTP or sits behind TLS is not something the user should have to know
 * before the app has talked to it once. So a bare `192.168.1.50` becomes a
 * short, ordered list of guesses that [HermesEndpointRacer] can settle.
 */
object HermesAddressCandidates {

    /** Port the Hermes API server listens on unless the operator moved it. */
    const val DEFAULT_PORT = 8642

    /** Enough to cover scheme × port; more would just slow the probe down. */
    private const val MAX_CANDIDATES = 6

    /**
     * Base URLs to try, most likely first.
     *
     * An address that already carries a scheme and a port is taken at its word
     * and returned alone — someone who typed that much knows what they meant.
     */
    fun expand(raw: String): List<String> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<String>()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return emptyList()
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return emptyList()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (uri.port > 0) {
                candidates += trimmed
            } else {
                // No port given. A reverse proxy on the standard web port is as
                // plausible as a direct hit on the Hermes default, so try both.
                candidates += "${uri.scheme}://$host:$DEFAULT_PORT$path"
                candidates += trimmed
            }
        } else {
            val hostPart = trimmed.substringBefore('/')
            val path = trimmed.removePrefix(hostPart).trimEnd('/')
            if (hasExplicitPort(hostPart)) {
                candidates += "http://$trimmed"
                candidates += "https://$trimmed"
            } else {
                candidates += "http://$hostPart:$DEFAULT_PORT$path"
                candidates += "https://$hostPart:$DEFAULT_PORT$path"
                candidates += "https://$trimmed"
                candidates += "http://$trimmed"
            }
        }
        return candidates.take(MAX_CANDIDATES)
    }

    /**
     * True when the host part ends in `:<number>`.
     *
     * A bracketed IPv6 literal such as `[::1]` ends in `1]`, which is not a
     * number, so it is correctly read as having no port.
     */
    private fun hasExplicitPort(hostPart: String): Boolean =
        hostPart.substringAfterLast(':', "").toIntOrNull() != null
}
