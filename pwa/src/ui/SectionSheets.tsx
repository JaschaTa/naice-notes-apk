import { useState } from 'preact/hooks'
import { displayText, nextIconInput } from '../data/display.ts'
import type { Item, Section } from '../data/model.ts'
import { cssColor, SECTION_COLORS } from '../data/notes.ts'
import { Sheet } from './Sheet.tsx'
import { Tile } from './Tile.tsx'

export interface SectionDraft { name: string; emoji: string | null; color: number }

/** Create and edit share one sheet, like Android's SectionDialog. Name, icon and colour in one save. */
export function SectionSheet({ section, onSave, onClose }: {
  section?: Section; onSave: (draft: SectionDraft) => void; onClose: () => void
}) {
  const [name, setName] = useState(section?.name ?? '')
  const [emoji, setEmoji] = useState<string | null>(section?.emoji ?? null)
  const [color, setColor] = useState(section?.color ?? SECTION_COLORS[Math.floor(Math.random() * SECTION_COLORS.length)])
  const title = section ? 'Edit section' : 'New section'

  return (
    <Sheet onClose={onClose} label={title}>
      <h2>{title}</h2>
      <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) { onSave({ name: name.trim(), emoji, color }); onClose() } }}>
        <div class="label">Section name</div>
        <input class="field" value={name} autoFocus={!section} onInput={(e) => setName(e.currentTarget.value)} />

        <div class="label">Icon</div>
        <div class="icon-field">
          {/* The keyboard's emoji tab is the picker; nextIconInput keeps exactly one character. */}
          <input class="field" value={emoji ?? ''} placeholder="🙂"
            onInput={(e) => { const next = nextIconInput(emoji, e.currentTarget.value); setEmoji(next); e.currentTarget.value = next ?? '' }} />
          <Tile section={{ name: name || '•', emoji, color }} />
        </div>
        <p class="hint">Tap the field, then your keyboard's emoji key. Leave it empty to use the first letter.</p>

        <div class="label">Colour</div>
        <div class="swatches">
          {SECTION_COLORS.map((c) => (
            <button key={c} type="button" class={c === color ? 'swatch on' : 'swatch'} style={{ background: cssColor(c) }}
              aria-label={cssColor(c)} aria-pressed={c === color} onClick={() => setColor(c)} />
          ))}
        </div>

        <div class="sheet-actions">
          <button type="button" class="btn text" onClick={onClose}>Cancel</button>
          <button class="btn text" disabled={!name.trim()}>{section ? 'Save' : 'Create'}</button>
        </div>
      </form>
    </Sheet>
  )
}

/** The choice is the confirmation: each option carries its count, so the destructive one can't be mistaken. */
export function ClearSheet({ section, checked, total, onClearChecked, onClearAll, onClose }: {
  section: Section; checked: number; total: number; onClearChecked: () => void; onClearAll: () => void; onClose: () => void
}) {
  return (
    <Sheet onClose={onClose} label={`Clear "${section.name}"`}>
      <h2>Clear "{section.name}"</h2>
      <p class="sub">This can't be undone.</p>
      <button class="choice" disabled={checked === 0} onClick={() => { onClose(); onClearChecked() }}>
        <span>Checked notes</span><span>{checked}</span>
      </button>
      <button class="choice danger" disabled={total === 0} onClick={() => { onClose(); onClearAll() }}>
        <span>All notes</span><span>{total}</span>
      </button>
      <div class="sheet-actions"><button class="btn text" onClick={onClose}>Cancel</button></div>
    </Sheet>
  )
}

export function MoveSheet({ item, sections, onMove, onClose }: {
  item: Item; sections: Section[]; onMove: (sectionId: string) => void; onClose: () => void
}) {
  return (
    <Sheet onClose={onClose} label="Move to section">
      <h2>Move to section</h2>
      <p class="sub" style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{displayText(item)}</p>
      {sections.map((s) => (
        <button key={s.id} class="move-row" onClick={() => { onClose(); onMove(s.id) }}>
          <Tile section={s} />{s.name}
        </button>
      ))}
      <div class="sheet-actions"><button class="btn text" onClick={onClose}>Cancel</button></div>
    </Sheet>
  )
}
