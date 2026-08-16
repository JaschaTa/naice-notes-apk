# Naice Notes — development notes

Personal Android app, sideloaded onto a Samsung Galaxy S24 (SM-S921B). See [README](./README.md) for what the app is and how to build it from scratch.

Single developer, history is linear on `main`, no branches. Commit messages are short neutral summaries, not change logs.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` holds the SDK path plus two webhook configs — recipe scan (`RECIPE_SCAN_URL`, `RECIPE_SCAN_SECRET`) and vault task inbox (`INBOX_PUSH_URL`, `INBOX_PUSH_JWT_SECRET`). It's gitignored, and this repo is public, so **no URL or secret may ever be committed**. A missing key becomes `""` and the matching feature reports itself unconfigured rather than failing at the call site: without it the app still builds, only the scan button and the inbox push degrade. The two secrets must stay distinct — reusing one token across webhooks is prohibited by the company webhook-security rules.

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

**Room runs in WAL mode — `cat`ting just the `.db` file gives a stale snapshot (v1.4).** Pulling `databases/naice-notes.db` on its own omits everything still sitting in `naice-notes.db-wal`, so recent writes appear missing and it looks like data was lost. Copy `.db`, `.db-wal` and `.db-shm` together and let SQLite replay the log, or force a checkpoint first. Note the app sandbox has no `sqlite3` binary, so inspect the pulled copy on the host:

```bash
adb exec-out run-as com.jt.naicenotes cat databases/naice-notes.db > pre.db   # incomplete on its own!
```

**`og:` tags are not reliably in `<head>` (v1.4.1).** Pinterest emits them ~1.06 MB into a 1.1 MB document, so an early read cap silently finds nothing. `LinkPreviewClient` reads up to `MAX_HTML_BYTES` (2 MB) rather than a small head window. Find the real offset with `grep -abo 'og:title' page.html`.

**Sites allowlist link-preview crawlers by User-Agent (v1.4.2).** This is why a shared Etsy link previews in WhatsApp but not from a browser-shaped client. Measured against one Etsy listing:

| User-Agent | Result |
|---|---|
| Chrome (mobile) | 403 |
| `facebookexternalhit/1.1` | 403 |
| `Twitterbot/1.0` | 403 |
| `WhatsApp/2.23.20.0` | **200** |
| `Slackbot-LinkExpanding` | **200** |
| `Discordbot/2.0` | **200** |
| `NaiceNotes/1.4` (honest) | 403 |

Sites *want* shares to preview, so they admit crawlers they recognise and refuse everything else — claiming to be Chrome invites the most scrutiny, since a real Chrome would bring matching TLS fingerprints, JS and cookies. Etsy substring-matches the token, so `UserAgents.PREVIEW_BOT` names this app *and* carries a recognised one. `UserAgents.ORDERED` is tried in turn and only a **block** (401/403/451 → `BlockedException`) advances to the next; a 404 or a page with genuinely no metadata fails identically for every client, so there's no second request. Preview-bot goes first because it strictly out-performed the browser string on tested sites — it gets 200 from both Etsy and kaufland.de, which both 403 the browser one.

Caveat: this is User-Agent spoofing, milder than the Chrome claim it replaced but spoofing nonetheless. If a site starts verifying crawlers by published IP range (as Meta and Slack do), it stops working and the item falls back to its slug label.

**kaufland.de rate-limits repeats.** It serves the first request then 403s identical follow-ups, which is why one duplicate previewed and its twin didn't. `PermanentFetchException` sets `linkFetchFailed` so launch-time retry stops hammering it; `Item.displayText` falls back to a URL-slug label so blocked links still read sensibly.

`linkFetchFailed` records a failure under the *then-current* UA policy, so changing `UserAgents.ORDERED` should come with a migration that clears it — `MIGRATION_3_4` is the precedent.

**Strip scheme *and* host before deriving a slug label (v1.4.1).** `https://www.kaufland.de/` otherwise treats the hostname as a path segment and renders "Www.kaufland". Covered by `ItemDisplayTextTest`, which caught exactly this.

**The widget renders `item.displayText`, not `item.text` (v1.4.1).** Otherwise link rows show raw tracking URLs in the widget while the app shows proper titles.

**Wikimedia 403s image requests from unrecognised clients (v1.4).** An `og:image` on `upload.wikimedia.org` fetched fine via OkHttp but failed to load in Coil, because Coil's default User-Agent gets refused. Link thumbnails therefore build an `ImageRequest` with an explicit browser User-Agent. Independently, the thumbnail draws its fallback icon *underneath* the `AsyncImage` rather than in an `else` branch — a failed load then reveals the icon instead of leaving an empty box.

## Debugging on a locked device

Two traps cost real time here, both of which make a working app look broken:

**A dozing phone can't resolve DNS for background work.** Launching via `adb shell monkey` while the screen is locked runs the app, but network fetches fail in ~5s with `UnknownHostException: No address associated with hostname` — not a timeout. It looks like a bug in the fetch code and isn't. Wake and unlock before judging anything network-related.

**logcat drops stack-trace continuation lines under a tag filter.** `Log.w(TAG, msg, throwable)` printed only the message, so the cause was invisible exactly when needed. Failures therefore log the exception **inline** in the message string.

**Unquoted globs in `zsh` silently sabotage diagnosis — this has bitten three times.** `logcat -s NaiceNotes:*` and `grep -r … --include=*.kt` both die with "no matches found" *before* running, and in a compound command the surrounding output makes it look like the tool ran and found nothing. Quote the argument: `logcat "NaiceNotes:I" "*:S"`, `grep -r "pattern" app/src/`. Related: never pipe logcat through `head` while grepping — install-time noise fills the first screenful, and it's easy to conclude wrongly that the app logged nothing.

`retryMissingLinkPreviews()` logs its queue size at `I` on every launch — that one line distinguishes "the query found nothing" from "the fetch failed", which was the crux of this diagnosis.

## Layout invariants worth not breaking

- Items are ordered `position ASC, createdAt ASC`. New items go to the **top**: the DAO's `insertAtTop` / `insertAllAtTop` shift existing rows down inside a `@Transaction`, so a partial shift can't scramble a section.
- Undo-delete deliberately restores an item to its *original* position, not the top.
- Inside a bounded `Column`, a `LazyColumn` sibling must use `weight(1f)`, not `fillMaxSize()` — the latter requests the full parent height and overflows by the height of whatever sits beside it.
- Link rows are capped at a single-line title (`maxLines = 1`) so they stay a predictable two lines tall. Variable-height cards were explicitly rejected during design — see `design-mockups/link-0*.html`.

## Icons and theme

**The app depends on `material-icons-core`, not `-extended`.** Extended is a 34 MB artifact shipping ~6,400 icons × 5 themes; this app uses 8, and with `isMinifyEnabled = false` nothing tree-shakes it — it accounted for roughly half the debug APK (69 MB → 36 MB when swapped). A new icon therefore needs **either** a name that exists in core **or** a vector drawable in `res/drawable`. Three already went that route: `ic_widget_check_off` (shared with the widget; identical to Material's `radio_button_unchecked`), `ic_link`, `ic_photo_camera`. Use `Icon(painter = painterResource(...), tint = ...)` — tint semantics match the `imageVector` overload.

**`NaiceNotesTheme` is always dynamic (Material You), with no fixed-palette fallback.** minSdk is 34, so dynamic colour is guaranteed and the old `Build.VERSION.SDK_INT >= S` branch was dead. Every accent in the app comes from `Section.color`, not the scheme.

**Section colours are persisted as ARGB ints** via the framework `androidx.compose.ui.graphics.toArgb`. Two hand-rolled `Color.toArgb()` extensions used to shadow it; they were provably equivalent (verified across all 256 channel values) and are gone. `SectionColorTest` locks the palette-to-literal mapping — if that test fails, stored colours are at risk.

## Database migrations

**Currently at schema version 5**, with `MIGRATION_1_2` (link-preview columns), `MIGRATION_2_3` (`linkFetchFailed`), `MIGRATION_3_4` (clears `linkFetchFailed` after the UA-policy change) and `MIGRATION_4_5` (`sections.remoteKind`, `items.pushedAt`) all registered in `AppDatabase.get()`. The next schema change is 5→6.

The DB holds the only copy of real notes and there is no export yet, so `AppDatabase` deliberately does **not** call `fallbackToDestructiveMigration()`. Every schema change needs a real `Migration`; adding nullable columns needs no backfill. Verify an upgrade against populated data before shipping — install over the previous build and confirm `PRAGMA user_version` advanced and row counts held (reading the WAL, per the gotcha above). Take a backup first: `~/naice-notes-backups/` holds one set per migration so far.

## Tests

```bash
./gradlew :app:testDebugUnitTest      # JVM only, no device needed
```

Three suites, all plain JVM — deliberately no Compose UI tests, because every run would need an emulator and the emulator can't reproduce the One UI launcher or IME behaviour where the real layout risk lives.

- `ItemDisplayTextTest` — `Item.displayText` / `linkDomain`, including the slug fallback for sites that block metadata fetches.
- `SectionColorTest` — palette-to-ARGB mapping. **If this fails, persisted section colours are at risk.**
- `InboxPushTest` — issue title derivation, dedupe-key stability and the JWT wire format for the inbox push. The dedupe assertions are the load-bearing ones: if the key stops being stable, a retry creates a duplicate task.

`androidTest/` is empty but its Gradle config is kept on purpose: zero runtime cost, and it's what a first instrumented test would need. The data layer is untested — `NotesRepository`, the DAO position-shift invariant and the migrations all have no coverage.

## Link previews

Share target (`share/ShareTargetActivity`) accepts `text/plain`, extracts a URL via `LinkDetector`, and hands the text to the repository. `NotesRepository.addItem` detects the URL and fires the `onLinkDetected` callback, which `NaiceNotesApp` wires to a background Open Graph fetch — so *every* add path (composer, widget quick-add, share) gets previews without knowing about networking. Fetching is direct from the device, best-effort: a failure leaves the raw URL showing, and `retryMissingLinkPreviews()` retries on next launch for links shared while offline.

## Vault task inbox push

A section can be marked as an inbox (section ⋮ → "Send new notes to Claude", stored as `sections.remoteKind = 'inbox'`). Notes **created** in it are POSTed to an n8n webhook that opens a GitLab issue in the vault's task inbox, which `/process-tasks` later turns into a real task. Capture only — nothing is ever read back, and there is no way to promote a note that already lives in another section (the app has no move-between-sections operation).

The mechanism deliberately copies link previews: `onInboxItem` is a repository callback in the same shape as `onLinkDetected`, so composer, share target and widget quick-add all push without any of them knowing. `InboxPushClient` mirrors `RecipeScanClient`, and `retryPendingInboxPushes()` sits next to `retryMissingLinkPreviews()` in `onCreate`.

Two things that differ from link previews, both on purpose:

- **No give-up flag.** There is no `pushedAt` counterpart to `linkFetchFailed`. A dead URL is worth abandoning; a captured task that never reached the inbox is not, so "never sent" and "send failed" are the same state and get retried forever.
- **Auth is a signed JWT, not a static header secret.** `JwtSigner` hand-rolls HS256 (~15 lines, no library) and mints a fresh short-lived token per request — including on the retry path, which must never reuse a stored token or it arrives expired. Company rules rank JWT above a static bearer secret for callers we control. Note the honest limit: a secret inside a sideloaded APK is extractable, so what expiry buys is bounded replay, not secrecy.

Item identity for the webhook's duplicate check is `inboxDedupeKey(id, createdAt)` — immutable fields only, so editing a note before a retry lands doesn't make it look like a new capture. `InboxPushTest` locks this.
