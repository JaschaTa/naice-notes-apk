# Redesign handoff — 2026-08-16

Open `index.html` for all mockups. Five app directions plus a widget set, borrowing from Slack
(structure) and Miro (surfaces).

**Picked and built: `redesign-01-slack-rail.html` + widget `W1`.** Shipped to the S24 and verified
against real data. The other four app directions are kept for reference:
`redesign-02-slack-groups.html`, `redesign-03-miro-canvas.html`, `redesign-04-miro-float.html`,
`redesign-05-hybrid.html`.

## Built

- **Section emoji** — nullable `emoji` on `Section`, `MIGRATION_5_6`. `Section.glyph` falls back to
  the name's first letter; `hasEmoji` drives the styling split (emoji sits on a 22% wash of the
  section colour, a letter takes white-on-solid). Picker lives in the rename/create dialog and
  previews the letter fallback on its "none" chip.
- **App rail** — 70dp, fixed near-black in both themes, all sections visible with open-count badges,
  active indicator bar, drag-to-reorder preserved.
- **Channel header** — glyph + name + count, and the per-section menu the bottom toolbar used to hold.
- **Row actions** — trailing `⋮` per row: toggle / edit / open link / move to section / delete.
- **Boxed composer** — with scan in its tool rail. `BottomToolbar` is gone.
- **Move to section** — new capability. `ItemDao.moveToSectionTop` lands the item at the top of the
  target, transactionally.
- **Widget W1** — name pills replaced by emoji tiles, plus a caption line carrying the active
  section's name and counts.

## Deviations from the mockup — worth reviewing

1. **No press-reveal action bar.** Every gesture on a row was already taken: tap the circle toggles,
   tap the text edits, long-press drags, swipe deletes. The actions hang off an explicit `⋮` instead.
   Same four actions, and unlike the gestures they replace, it's visible. Costs a permanent icon per
   row.
2. **No link/voice buttons in the composer.** Nothing backs them. The mockup drew four tools; only
   scan and send exist, so only those are drawn.
3. **Rail is a fixed near-black**, not Material You. A dynamic mid-tone fights nine saturated section
   colours; a neutral dark doesn't.

## Verified

Migration ran on the real device: v5 → v6, **7 sections and 45 items intact**, `emoji` null everywhere
so every section renders its letter. Backup at `~/naice-notes-backups/pre-v6-emoji/` (`.db`, `.db-wal`,
`.db-shm` — the WAL was 409 KB against a 32 KB db, so it matters). Emoji path exercised end-to-end on
the emulator. Widget confirmed on One UI: all 7 sections fit as tiles where roughly three name pills
did, which was the whole argument for the change.

## Open

- W2 (rail widget, 4×3) and W3 (2×2 focus) are designed but not built.
- Sections still have no emoji set — they're all on the letter fallback until picked via
  **⋮ → Rename & icon**.
- Unit tests still cover only `ItemDisplayTextTest` and `SectionColorTest`. `Section.glyph`, the
  fallback and `moveToSectionTop` have no coverage.
