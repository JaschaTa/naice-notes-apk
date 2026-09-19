package com.jt.naicenotes.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The clock the due/waiting split is judged against, re-read whenever the app comes back to
 * the foreground.
 *
 * Nothing in this app runs on a timer, so this is the only thing that advances time as far as
 * the UI is concerned: left open and never backgrounded, a note will not cross from waiting to
 * due. That's the accepted cost of having no scheduler — a foreground ticker would fix it and
 * would also reshuffle the list under a finger mid-drag.
 *
 * Hoist this **once** per screen and pass the value down. Anything that calls
 * `System.currentTimeMillis()` inline instead can disagree with it within a single frame, and
 * a list whose partition disagrees with its own counts is the bug this exists to prevent.
 */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        now.longValue = System.currentTimeMillis()
    }
    return now
}
