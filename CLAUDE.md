# Naice Notes — development notes

Personal Android app, sideloaded onto a Samsung Galaxy S24 (SM-S921B). See [README](./README.md) for what the app is and how to build it from scratch.

Single developer, history is linear on `main`, no branches. Commit messages are short neutral summaries, not change logs.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` holds the SDK path plus the recipe-scan webhook URL and secret. It's gitignored; without it the app still builds and only the scan button degrades.

## Testing on the emulator

AVD `Pixel_9` — API 36, arm64-v8a, an exact match for `targetSdk 36`. Fastest loop for layout work.

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Two traps that silently invalidate UI tests — both cost real time before being identified:

- **`hw.keyboard=yes`** in `~/.android/avd/Pixel_9.avd/config.ini` routes typing to the host keyboard and suppresses the soft keyboard entirely. Per-session fix: `adb shell settings put secure show_ime_with_hard_keyboard 1`.
- **Gboard in floating mode reports zero IME insets**, so any keyboard/inset change appears to do nothing. Reset with `adb shell pm clear com.google.android.inputmethod.latin`.

The emulator runs the AOSP/Pixel launcher and stock Gboard. It verifies layout logic but will **not** reproduce One UI launcher or IME behaviour — anything touching the widget or keyboard insets needs the real device before you trust it.

## Testing on the S24 (wireless ADB)

The debugging port rotates every session. Discover it rather than reading it off the phone:

1. Phone: Developer options → Wireless debugging → **ON**
2. `adb mdns services` → prints the `_adb-tls-connect._tcp` endpoint
3. `adb connect <ip>:<port>`

**Pairing does not survive OS updates.** If `adb connect` fails but `nc -z <ip> <port>` succeeds, that's a TLS/auth failure rather than a network one — re-pair via "Pair device with pairing code" (which uses a *different* port from the connect service):

```bash
adb pair <ip>:<pairing-port> <6-digit-code>
```

Networks with client isolation (many public/transport hotspots) block device-to-device TCP entirely; use USB there.

## Platform gotcha ledger

Append here whenever a session burns time on a non-obvious platform quirk. Symptom → cause → fix → how to diagnose.

**Widget renders blank on One UI 8.5 / Android 16 (v1.1).** `notes_widget_info.xml` never declared `android:initialLayout`. Pre-8.5 launchers tolerated its absence, but HoneySpace inflates the initial layout as the first paint — with `initialLayout=#0` it painted nothing, and nothing re-triggered a paint (`updatePeriodMillis=0`, app not running). Fix: `android:initialLayout="@layout/widget_main"`. Diagnose with `adb shell dumpsys appwidget` and look for `initialLayout=#0`.

**Widget-launched activities need `android:taskAffinity=""` (v1.2).** `QuickAddActivity` opened the whole app behind its dialog because it shared `MainActivity`'s default task affinity and got stacked into that task on `FLAG_ACTIVITY_NEW_TASK`.

**SwipeToDismissBox dismisses on velocity, not just distance (v1.3).** Material3 1.4.0's `AnchoredDraggable` settles on fling velocity **or** position, and on the fling path `positionalThreshold` is never consulted — so raising it does nothing for quick flicks, which is the accidental-delete case. Material3 1.4.0 exposes no `velocityThreshold` (verified with `javap` against the material3 AAR). The only working guard is to read `state.requireOffset()` — the actual finger travel at release — inside `confirmValueChange` and veto anything shorter than `SWIPE_DELETE_FRACTION` of the row width. Related: `confirmValueChange` must return `true` for `Settled`, otherwise rows cannot spring back to rest.

**Edge-to-edge IME insets stack (v1.3).** With `enableEdgeToEdge()` the window never resizes for the keyboard, so Compose owns the inset. Applying `.imePadding()` to an *unweighted* `Column` child makes its measured height `content + full IME height`, and any `weight(1f)` sibling absorbs the entire loss — the item list collapsed toward zero while the composer's padding filled the screen. Note that `Scaffold`'s `contentWindowInsets.asPaddingValues()` reads *raw* insets, so it does not observe consumption from a nested `imePadding()` either. Fix is one source of truth on the Scaffold:

```kotlin
contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime)
```

`union` takes the larger value per side — nav bar when the keyboard is closed, IME height when open, never the sum.

**Material `TextField` enforces a 56dp minimum height.** Too tall for a composer sitting above a keyboard. `BasicTextField` inside a styled `Box` is the compact alternative, used for both the composer and inline row editing.

## Layout invariants worth not breaking

- Items are ordered `position ASC, createdAt ASC`. New items go to the **top**: the DAO's `insertAtTop` / `insertAllAtTop` shift existing rows down inside a `@Transaction`, so a partial shift can't scramble a section.
- Undo-delete deliberately restores an item to its *original* position, not the top.
- Inside a bounded `Column`, a `LazyColumn` sibling must use `weight(1f)`, not `fillMaxSize()` — the latter requests the full parent height and overflows by the height of whatever sits beside it.
