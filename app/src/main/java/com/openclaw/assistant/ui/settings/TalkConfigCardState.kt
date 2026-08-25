package com.openclaw.assistant.ui.settings

/**
 * What the read-only Talk settings card should show.
 *
 * Split out of the composable because the card used to spin forever: it asked
 * only "has a fetch completed?", and when the gateway was unreachable the fetch
 * never started, so that answer stayed "no" for good. Whether the relay is even
 * usable has to be part of the decision, not something the card is unaware of.
 */
enum class TalkConfigCardState {
    /** A request is in flight, or about to be. */
    Loading,

    /** The gateway is not reachable, so there is nothing to read yet. */
    Offline,

    /** The gateway answered, but the configuration could not be read. */
    Failed,

    /** Values are available. */
    Loaded,
    ;

    companion object {
        fun of(
            relayUsable: Boolean,
            loading: Boolean,
            fetched: Boolean,
            hasSummary: Boolean,
        ): TalkConfigCardState = when {
            loading -> Loading
            // Checked before [fetched] on purpose: an unreachable gateway means
            // no attempt was made, and reporting that is the whole point.
            !relayUsable -> Offline
            !fetched -> Loading
            !hasSummary -> Failed
            else -> Loaded
        }
    }
}
