// Port of Android's data/util/Schedule.kt, ui/util/DateFormat.kt and the due-state rules on Item.
// All calendar arithmetic is in the device's local time zone, like ZoneId.systemDefault() there.
import type { Item } from './model.ts'

const DAY_MS = 86_400_000

export const isScheduled = (i: Pick<Item, 'dueAt' | 'repeatWeeks'>) => i.dueAt != null || i.repeatWeeks != null
export const isRecurring = (i: Pick<Item, 'repeatWeeks'>) => i.repeatWeeks != null
/** isDue and isWaiting are not complements: a note with no dueAt is neither. */
export const isDue = (i: Pick<Item, 'dueAt'>, now: number) => i.dueAt != null && i.dueAt <= now
export const isWaiting = (i: Pick<Item, 'dueAt'>, now: number) => i.dueAt != null && i.dueAt > now

export interface SectionCounts { open: number; due: number }

/**
 * Open and due counts per section at `now`. Waiting and checked notes count towards neither, and
 * a section with nothing open is absent rather than present with zero.
 */
export function countsBySection(items: Pick<Item, 'sectionId' | 'isChecked' | 'dueAt'>[], now: number): Map<string, SectionCounts> {
  const counts = new Map<string, SectionCounts>()
  for (const i of items) {
    if (i.isChecked || isWaiting(i, now)) continue
    const c = counts.get(i.sectionId) ?? { open: 0, due: 0 }
    counts.set(i.sectionId, { open: c.open + 1, due: c.due + (i.dueAt != null ? 1 : 0) })
  }
  return counts
}

/** Checked and total per section, ignoring the clock — clearing removes waiting notes too. */
export function totalsBySection(items: Pick<Item, 'sectionId' | 'isChecked'>[]): Map<string, { checked: number; total: number }> {
  const totals = new Map<string, { checked: number; total: number }>()
  for (const i of items) {
    const t = totals.get(i.sectionId) ?? { checked: 0, total: 0 }
    totals.set(i.sectionId, { checked: t.checked + (i.isChecked ? 1 : 0), total: t.total + 1 })
  }
  return totals
}

/**
 * When a repeating note is next owed: calendar weeks, not 7 × 24 h. setDate works on the local
 * calendar, so a repeat crossing a DST change keeps its wall-clock time instead of drifting an hour.
 */
export function nextDueAt(from: number, repeatWeeks: number): number {
  const d = new Date(from)
  d.setDate(d.getDate() + repeatWeeks * 7)
  return d.getTime()
}

/** Local midnight `days` days after `from` — "in 2 weeks" means that day, not that hour of it. */
export function startOfDayIn(from: number, days: number): number {
  const d = new Date(from)
  d.setDate(d.getDate() + days)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

/** A <input type="date"> value ("2026-10-03") as local midnight. */
export function dateInputToLocalStartOfDay(value: string): number {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, m - 1, d).getTime()
}

/** Local date of an instant as "YYYY-MM-DD", for <input type="date">. */
export function toDateInputValue(at: number): string {
  const d = new Date(at)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Whole calendar days between the two local dates (DST days are 23 or 25 h, hence the round). */
function calendarDaysBetween(from: number, to: number): number {
  return Math.round((startOfDayIn(to, 0) - startOfDayIn(from, 0)) / DAY_MS)
}

/** "today", "tomorrow", "in 5d", "in 3w", "yesterday", "3d ago" — same rules as Android. */
export function formatDueRelative(dueAt: number, now: number): string {
  const days = calendarDaysBetween(now, dueAt)
  if (days === 0) return 'today'
  if (days === 1) return 'tomorrow'
  if (days === -1) return 'yesterday'
  if (days < -1) return `${-days}d ago`
  if (days <= 13) return `in ${days}d`
  return `in ${Math.floor(days / 7)}w`
}

/** The due date itself; a due date has no useful time of day. */
export function formatDueDate(dueAt: number, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric' }).format(dueAt)
}
