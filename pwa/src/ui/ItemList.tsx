import { useEffect, useRef, useState } from 'preact/hooks'
import { displayText, linkDomain } from '../data/display.ts'
import type { Item } from '../data/model.ts'
import { formatDueDate, formatDueRelative, isDue, isRecurring, isScheduled, isWaiting } from '../data/schedule.ts'
import { CheckCircle, LinkIcon, MoreVert, RadioUnchecked } from './icons.tsx'
import { useRowGestures } from './useRowGestures.ts'

export interface ItemActions {
  toggle: (item: Item) => void
  editText: (item: Item, text: string) => void
  delete: (item: Item) => void
  reorder: (id: string, before: Item | null, after: Item | null) => void
  openMenu: (item: Item) => void
  makeActiveNow: (item: Item) => void
  removeDue: (item: Item) => void
  resetTimer: (item: Item) => void
}

/**
 * Active notes, then a "NOT DUE YET" divider and the waiting ones — split once, on one `now`, so a
 * row lands in exactly one block. Only the active block can be dragged.
 */
export function ItemList({ items, now, actions }: { items: Item[]; now: number; actions: ItemActions }) {
  const active = items.filter((i) => !isWaiting(i, now))
  const waiting = items.filter((i) => isWaiting(i, now))
  const { bind, drag, swipe, offsetFor } = useRowGestures({
    ids: active.map((i) => i.id),
    swipeIds: items.map((i) => i.id),
    onSwipe: (id) => { const it = items.find((i) => i.id === id); if (it) actions.delete(it) },
    onReorder: (id, to) => {
      // Neighbours within the active block only, so a drag never renumbers the block below.
      const rest = active.filter((i) => i.id !== id)
      actions.reorder(id, rest[to - 1] ?? null, rest[to] ?? null)
    },
  })

  const row = (item: Item) => (
    <Row key={item.id} item={item} now={now} actions={actions} bind={bind(item.id)}
      dy={offsetFor(item.id)} dragging={drag?.id === item.id} anyDragging={!!drag}
      dx={swipe?.id === item.id ? swipe.dx : 0} />
  )

  return (
    <div class="list">
      {active.map(row)}
      {waiting.length > 0 && <div class="not-due">NOT DUE YET</div>}
      {waiting.map(row)}
    </div>
  )
}

function Row({ item, now, actions, bind, dy, dx, dragging, anyDragging }: {
  item: Item; now: number; actions: ItemActions; bind: object
  dy: number; dx: number; dragging: boolean; anyDragging: boolean
}) {
  const [editing, setEditing] = useState(false)
  const due = isDue(item, now)
  const waiting = isWaiting(item, now)
  const cls = ['row', item.isChecked && 'checked', due && 'due', waiting && 'waiting', dragging && 'dragging'].filter(Boolean).join(' ')
  const transform = dx ? `translateX(${dx}px)` : dy ? `translateY(${dy}px)` : undefined

  return (
    // Clip only while swiping; otherwise the schedule tooltip must be able to overflow the row.
    <div class="row-wrap" style={{ zIndex: dragging ? 3 : undefined, overflow: dx ? 'hidden' : 'visible' }}>
      {dx !== 0 && <div class={dx > 0 ? 'swipe-bg left' : 'swipe-bg'}>Delete</div>}
      <div {...bind} class={cls}
        style={{ transform, transition: anyDragging && !dragging ? 'transform .15s' : dx ? 'none' : undefined }}>
        <button class="check" aria-label={item.isChecked ? 'Mark as open' : 'Mark as done'}
          style={{ color: item.isChecked ? 'var(--accent)' : 'var(--outline)' }} onClick={() => actions.toggle(item)}>
          {item.isChecked ? <CheckCircle /> : <RadioUnchecked />}
        </button>

        {item.linkUrl ? (
          <LinkContent item={item} />
        ) : editing ? (
          <InlineEdit text={item.text} onDone={(t) => { setEditing(false); if (t !== null) actions.editText(item, t) }} />
        ) : (
          <button class="row-text" onClick={() => setEditing(true)}>{item.text}</button>
        )}

        {!editing && isScheduled(item) && <ScheduleGlyph item={item} now={now} actions={actions} />}
        {!editing && (
          <button class="more" aria-label="More" onClick={() => actions.openMenu(item)}><MoreVert /></button>
        )}
      </div>
    </div>
  )
}

/** Enter or blur commits the trimmed text; empty or unchanged reverts. */
function InlineEdit({ text, onDone }: { text: string; onDone: (text: string | null) => void }) {
  const ref = useRef<HTMLInputElement>(null)
  const done = useRef(false)
  useEffect(() => { ref.current?.focus(); ref.current?.setSelectionRange(text.length, text.length) }, [])
  const finish = (value: string | null) => {
    if (done.current) return
    done.current = true
    const t = value?.trim()
    onDone(t && t !== text ? t : null)
  }
  return (
    <input ref={ref} class="row-edit" defaultValue={text} enterKeyHint="done"
      onKeyDown={(e) => {
        if (e.key === 'Enter') finish(e.currentTarget.value)
        if (e.key === 'Escape') finish(null)
      }}
      onBlur={(e) => finish(e.currentTarget.value)} />
  )
}

/** A link row has no edit mode: tapping opens the page, the only thing to do with a URL. */
function LinkContent({ item }: { item: Item }) {
  const [imgFailed, setImgFailed] = useState(false)
  return (
    <a class="link-row" href={item.linkUrl!} target="_blank" rel="noopener noreferrer">
      <span class="thumb">
        <LinkIcon />
        {item.linkImageUrl && !imgFailed && <img src={item.linkImageUrl} alt="" loading="lazy" referrerpolicy="no-referrer" onError={() => setImgFailed(true)} />}
      </span>
      <span class="link-text">
        <div class="link-title">{displayText(item)}</div>
        {linkDomain(item) && <div class="link-domain">{linkDomain(item)}</div>}
      </span>
    </a>
  )
}

/**
 * ⏳ waiting, 🔁 repeating, ⏰ otherwise — one glyph per state. Tapping shows the date and the
 * one action that state allows.
 */
function ScheduleGlyph({ item, now, actions }: { item: Item; now: number; actions: ItemActions }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: Event) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [open])

  const waiting = isWaiting(item, now)
  const glyph = waiting ? '⏳' : isRecurring(item) ? '🔁' : '⏰'
  const title = item.dueAt != null ? `Due ${formatDueRelative(item.dueAt, now)}` : 'Repeating'
  const repeats = item.repeatWeeks == null ? '' : item.repeatWeeks === 1 ? 'repeats weekly' : `repeats every ${item.repeatWeeks} weeks`
  const body = [item.dueAt != null ? formatDueDate(item.dueAt) : null, repeats || null].filter(Boolean).join(' · ')
  const action = waiting
    ? { label: 'Make active now', run: () => actions.makeActiveNow(item) }
    : isRecurring(item)
      ? { label: 'Reset timer', run: () => actions.resetTimer(item) }
      : { label: 'Remove due', run: () => actions.removeDue(item) }
  // Near the top of the screen there's no room above the glyph.
  const below = open && (ref.current?.getBoundingClientRect().top ?? 999) < 160

  return (
    <span ref={ref} style={{ position: 'relative' }}>
      <button class="glyph-btn" aria-label={title} aria-expanded={open} onClick={() => setOpen(!open)}>{glyph}</button>
      {open && (
        <div class={below ? 'tooltip below' : 'tooltip'} role="tooltip">
          <div class="tooltip-title">{title}</div>
          {body && <div class="tooltip-body">{body}</div>}
          <button class="btn text" onClick={() => { setOpen(false); action.run() }}>{action.label}</button>
        </div>
      )}
    </span>
  )
}
