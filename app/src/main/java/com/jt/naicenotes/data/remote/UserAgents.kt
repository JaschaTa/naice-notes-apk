package com.jt.naicenotes.data.remote

import com.jt.naicenotes.BuildConfig

/**
 * User-Agent strings used when fetching link metadata and thumbnails.
 *
 * A User-Agent is self-reported and unverified, so this is where we decide how the app
 * introduces itself. Two facts drove these values:
 *
 *  - Sites that *want* their pages to preview nicely (Etsy, for one) run allowlists for
 *    known link-preview crawlers and refuse everything else — including a plain Chrome
 *    string, `facebookexternalhit` and `Twitterbot`. Etsy substring-matches the token, so
 *    [PREVIEW_BOT] names this app *and* carries a recognised one.
 *  - Others (Wikimedia) refuse anything that doesn't look like a browser, hence [BROWSER].
 *
 * Neither is fully truthful, which is worth being clear-eyed about. "I am fetching this to
 * render a link preview" is at least closer to what's happening than "I am Chrome".
 */
object UserAgents {

    /** Gets past preview-crawler allowlists. Names the app so it's identifiable in logs. */
    const val PREVIEW_BOT =
        "NaiceNotes/${BuildConfig.VERSION_NAME} (link preview; compatible; WhatsApp/2.23.20.0)"

    /** For sites that only serve browsers. Also used for thumbnail requests. */
    const val BROWSER =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36"

    /**
     * Tried in order, stopping at the first that isn't refused. Preview-bot first because
     * it succeeds strictly more often on the sites tested — notably it gets 200 from both
     * Etsy and kaufland.de, which both 403 the browser string.
     */
    val ORDERED = listOf(PREVIEW_BOT, BROWSER)
}
