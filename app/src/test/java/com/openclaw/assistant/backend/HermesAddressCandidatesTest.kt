package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is willing to guess from a typed address.
 *
 * The point of the guided flow is that a user types `192.168.1.50` and nothing
 * else, so the expansion has to cover the realistic deployments without turning
 * one probe into a dozen.
 */
class HermesAddressCandidatesTest {

    @Test fun `a bare ip tries the hermes port on both schemes first`() {
        val candidates = HermesAddressCandidates.expand("192.168.1.50")
        assertEquals("http://192.168.1.50:8642", candidates[0])
        assertEquals("https://192.168.1.50:8642", candidates[1])
        // A reverse proxy on the standard web port is the less likely case, so
        // it comes after, but it is still tried.
        assertTrue(candidates.contains("https://192.168.1.50"))
        assertTrue(candidates.contains("http://192.168.1.50"))
    }

    @Test fun `a hostname is expanded the same way`() {
        val candidates = HermesAddressCandidates.expand("hermes.local")
        assertEquals("http://hermes.local:8642", candidates.first())
    }

    @Test fun `an explicit port is taken at face value`() {
        val candidates = HermesAddressCandidates.expand("192.168.1.50:9000")
        assertEquals(listOf("http://192.168.1.50:9000", "https://192.168.1.50:9000"), candidates)
    }

    @Test fun `a full url with scheme and port is used alone`() {
        assertEquals(
            listOf("https://hermes.example.com:8443"),
            HermesAddressCandidates.expand("https://hermes.example.com:8443"),
        )
    }

    @Test fun `a url with a scheme but no port also tries the hermes port`() {
        val candidates = HermesAddressCandidates.expand("https://hermes.example.com")
        assertEquals("https://hermes.example.com:8642", candidates[0])
        assertEquals("https://hermes.example.com", candidates[1])
    }

    @Test fun `a reverse-proxy path is preserved`() {
        val candidates = HermesAddressCandidates.expand("https://example.com/hermes")
        assertTrue("was $candidates", candidates.all { it.endsWith("/hermes") })
    }

    @Test fun `a bare host with a path keeps the path`() {
        val candidates = HermesAddressCandidates.expand("example.com/hermes")
        assertEquals("http://example.com:8642/hermes", candidates.first())
    }

    @Test fun `a trailing slash does not create a separate guess`() {
        assertEquals(
            HermesAddressCandidates.expand("192.168.1.50"),
            HermesAddressCandidates.expand("192.168.1.50/"),
        )
    }

    @Test fun `surrounding whitespace is ignored`() {
        assertEquals(
            HermesAddressCandidates.expand("192.168.1.50"),
            HermesAddressCandidates.expand("  192.168.1.50  "),
        )
    }

    @Test fun `an ipv6 literal is not mistaken for a host and port`() {
        val candidates = HermesAddressCandidates.expand("[fd00::1]")
        assertEquals("http://[fd00::1]:8642", candidates.first())
    }

    @Test fun `an ipv6 literal with a port is taken as given`() {
        assertEquals(
            listOf("http://[fd00::1]:8642", "https://[fd00::1]:8642"),
            HermesAddressCandidates.expand("[fd00::1]:8642"),
        )
    }

    @Test fun `blank input yields nothing to try`() {
        assertTrue(HermesAddressCandidates.expand("").isEmpty())
        assertTrue(HermesAddressCandidates.expand("   ").isEmpty())
    }

    @Test fun `the guess list stays short enough to probe quickly`() {
        assertTrue(HermesAddressCandidates.expand("192.168.1.50").size <= 6)
    }

    @Test fun `every candidate is a url the client can normalize`() {
        // A guess the rest of the stack cannot parse would fail late and
        // confusingly, so prove they all survive normalization.
        listOf("192.168.1.50", "hermes.local", "https://example.com/hermes", "[fd00::1]").forEach { raw ->
            HermesAddressCandidates.expand(raw).forEach { candidate ->
                val normalized = HermesUrl.normalizeBase(candidate)
                assertTrue("$candidate -> $normalized", normalized.endsWith("/v1"))
            }
        }
    }
}
