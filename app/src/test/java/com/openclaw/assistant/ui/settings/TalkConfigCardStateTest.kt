package com.openclaw.assistant.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-only Talk settings card.
 *
 * It used to spin forever whenever the gateway was unreachable: the card asked
 * only whether a fetch had completed, the fetch was never started, and so the
 * answer stayed "no" permanently. Every combination is pinned here because the
 * broken one looked exactly like a slow network.
 */
class TalkConfigCardStateTest {

    private fun state(
        relayUsable: Boolean = true,
        loading: Boolean = false,
        fetched: Boolean = true,
        hasSummary: Boolean = true,
    ) = TalkConfigCardState.of(relayUsable, loading, fetched, hasSummary)

    @Test fun `an unreachable gateway is reported, not spun on forever`() {
        // The regression: no fetch is attempted, so `fetched` never becomes
        // true, and the card must not read that as "still loading".
        assertEquals(TalkConfigCardState.Offline, state(relayUsable = false, fetched = false))
    }

    @Test fun `an unreachable gateway stays reported even after an earlier success`() {
        assertEquals(
            TalkConfigCardState.Offline,
            state(relayUsable = false, fetched = true, hasSummary = true),
        )
    }

    @Test fun `a request in flight shows progress`() {
        assertEquals(TalkConfigCardState.Loading, state(loading = true, fetched = false))
    }

    @Test fun `progress wins over everything else while it is in flight`() {
        assertEquals(TalkConfigCardState.Loading, state(relayUsable = false, loading = true))
    }

    @Test fun `the gap before the first request also shows progress`() {
        assertEquals(TalkConfigCardState.Loading, state(fetched = false, hasSummary = false))
    }

    @Test fun `a reachable gateway that answers nothing is a failure, not a wait`() {
        assertEquals(TalkConfigCardState.Failed, state(fetched = true, hasSummary = false))
    }

    @Test fun `values are shown once they arrive`() {
        assertEquals(TalkConfigCardState.Loaded, state())
    }

    @Test fun `no combination is left spinning once a fetch has resolved`() {
        // Whatever the outcome, a finished attempt must land on something the
        // user can act on.
        for (relayUsable in listOf(true, false)) {
            for (hasSummary in listOf(true, false)) {
                val resolved = state(relayUsable = relayUsable, loading = false, fetched = true, hasSummary = hasSummary)
                assertEquals(
                    "relayUsable=$relayUsable hasSummary=$hasSummary still shows a spinner",
                    false,
                    resolved == TalkConfigCardState.Loading,
                )
            }
        }
    }
}
