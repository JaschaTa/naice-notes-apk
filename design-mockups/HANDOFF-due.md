# Timed & recurring tasks handoff — 2026-09-19

Open `index.html` for the set. Four mockups: two volumes of the same list treatment, the tooltip
states, and the composer modal. Dark, unlike the 2026-08 round, because the whole decision was
muted-vs-emphasised contrast and a light frame misrepresents it.

**Picked and built: `due-02-loud.html`, minus its chips.** `due-01-quiet.html` is kept for reference —
it's the fallback if the amber treatment ever reads as too much. `due-03-tooltip-actions.html` and
`due-04-composer-modal.html` were both built close to the drawing.

## Built

- **Schema 6 → 7** — `items.dueAt` and `items.repeatWeeks`, both nullable, no backfill. Verified on real
  data twice: emulator (4 sections / 11 items) and the S24 (7 sections / 14 items), backup at
  `~/naice-notes-backups/pre-v7-due-s24/`.
- **The two-block list** — active rows, then a centred `NOT DUE YET` rule, then the waiting ones. One
  `partition` on one hoisted `now`, so the blocks can't disagree; `reorderWithin` translates drag indices
  by id because rendered order and stored order differ once a waiting row sits between two active ones.
- **Counts without a clock in the query** — `observeItemBuckets` groups by `(sectionId, isChecked, dueAt)`
  and `countsBySection` applies `now` in Kotlin. A `:now` parameter would bind once and the Flow would
  answer for that instant forever.
- **Single-action tooltips** — `RichTooltip` with one action each: "Make active now", "Remove due",
  "Reset timer". Ticking a repeating note off does nothing; only the reset restarts the cycle.
- **Timer dialog from two places** — the composer's calendar button (armed green, cleared on submit) and
  **Add timer / Edit timer** in a row's ⋮ menu, which is what lets a note already on the list get one.
- **Widget** — one `filterNot { it.isWaiting(now) }` at the load site covers both the rows and the caption,
  since `buildRemoteViews` takes the same list. A section holding only waiting notes says "Nothing due in
  X" rather than claiming to be empty.

## Deviations from the mockups — worth reviewing

1. **No relative-time chips anywhere on a row.** `due-02` drew "Due today", "3d late" and "in 12d". All
   three went: they cost width on a row that already carries a glyph and a ⋮, and the tooltip answers the
   same question on demand. Relative time survives only in the tooltip title, where being a day stale past
   midnight doesn't matter.
2. **The divider is a centred rule, not a filled band.** The band drawn in `due-02` sat at the same visual
   weight as the notes under it and read as one of them.
3. **A repeat with no date is owed straight away**, not one interval out as the `due-04` caption first
   claimed. Setting one up is usually the moment you're about to do the thing.
4. **`⏰`/`⏳`/`🔁` as drawn**, but a *waiting* recurring note shows ⏳, not 🔁 — its repeat-ness is in the
   tooltip. One glyph per state beats one glyph per property.
5. **No un-recur in the tooltip**, by choice — but Edit timer → Clear reintroduced the escape hatch from
   the ⋮ menu, which is where per-item actions already live.

## Verified

Every path driven on the emulator: each quick chip, the calendar, repeat-only, the three tooltip actions,
drag within the active block leaving waiting positions untouched, the armed button greying out Claude, and
the widget excluding waiting notes. Migration confirmed against the WAL on both devices. 61 JVM tests,
including the DST crossing — the one case plain millisecond week-arithmetic passes right up until it doesn't.

## Open

- **Nothing runs on a timer.** `rememberNow()` re-reads the clock on resume, so the app is right whenever
  you open it, but a note crossing its boundary with the app already open won't move until you background
  it. The widget is worse: `updatePeriodMillis="0"`, so it can sit stale for days. Both accepted; a punctual
  widget needs AlarmManager.
- **A newly-due note appears at its stored position**, not the top — there's no moment at which to move it.
- W2 (rail widget, 4×3) and W3 (2×2 focus) are still designed-not-built from the 2026-08 round.
- Still no coverage for `NotesRepository`, the DAO position-shift invariants or the migrations; all need an
  Android runtime. Migrations remain the highest-value gap.
