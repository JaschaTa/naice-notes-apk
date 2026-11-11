// Every local change the UI can make. Each one writes to the store (marking the row dirty) and
// then asks for a sync; nothing here talks to the network directly, so it all works offline.
import { generateKeyBetween } from 'fractional-indexing'
import { bySortKey, type Item, type Section } from './model.ts'
import { nextDueAt } from './schedule.ts'
import type { Store } from './store.ts'

/** Mirrors SectionColorPalette on Android, as signed ARGB ints the way Kotlin's toArgb() stores them. */
export const SECTION_COLORS: number[] = [
  0xff4caf50, 0xff2196f3, 0xffff9800, 0xffe91e63, 0xff9c27b0,
  0xfff44336, 0xff009688, 0xff795548, 0xff607d8b,
].map((c) => c | 0)

/** ARGB int → CSS colour. */
export function cssColor(argb: number): string {
  return `#${((argb >>> 0) & 0xffffff).toString(16).padStart(6, '0')}`
}

export class Notes {
  private store: Store
  private requestSync: () => void
  constructor(store: Store, requestSync: () => void = () => {}) {
    this.store = store
    this.requestSync = requestSync
  }

  // ── Reads ──

  async sections(): Promise<Section[]> {
    return (await this.store.all('sections')).filter((s) => !s.deletedAt).sort(bySortKey)
  }

  async items(sectionId: string): Promise<Item[]> {
    return (await this.store.itemsInSection(sectionId)).filter((i) => !i.deletedAt).sort(bySortKey)
  }

  // ── Sections ──

  async addSection(spaceId: string, name: string, emoji: string | null = null, color?: number): Promise<Section> {
    const existing = await this.sections()
    const last = existing[existing.length - 1]
    const section: Section = {
      id: crypto.randomUUID(), spaceId, name, emoji,
      color: color ?? SECTION_COLORS[Math.floor(Math.random() * SECTION_COLORS.length)],
      sortKey: generateKeyBetween(last?.sortKey ?? null, null),
      createdAt: Date.now(), deletedAt: null, rev: null, dirty: 1, version: 0,
    }
    await this.store.write('sections', section)
    this.requestSync()
    return section
  }

  async updateSection(id: string, patch: Partial<Pick<Section, 'name' | 'emoji' | 'color'>>): Promise<void> {
    const s = await this.store.get('sections', id)
    if (!s) return
    await this.store.write('sections', { ...s, ...patch })
    this.requestSync()
  }

  /** The server tombstones the section's items itself; locally they just disappear with it. */
  async deleteSection(id: string): Promise<void> {
    const s = await this.store.get('sections', id)
    if (!s) return
    await this.store.write('sections', { ...s, deletedAt: new Date().toISOString() })
    for (const item of await this.store.itemsInSection(id)) await this.store.discard('items', item.id)
    this.requestSync()
  }

  // ── Items ──

  /** New notes land at the top, as on Android. */
  async addItem(sectionId: string, text: string, schedule: { dueAt?: number | null; repeatWeeks?: number | null } = {}): Promise<Item> {
    const first = (await this.items(sectionId))[0]
    const item: Item = {
      id: crypto.randomUUID(), sectionId, text, isChecked: false,
      sortKey: generateKeyBetween(null, first?.sortKey ?? null),
      createdAt: Date.now(), createdBy: null,
      linkUrl: findUrl(text), linkTitle: null, linkImageUrl: null, linkFetchFailed: false,
      dueAt: schedule.dueAt ?? null, repeatWeeks: schedule.repeatWeeks ?? null,
      deletedAt: null, rev: null, dirty: 1, version: 0,
    }
    await this.store.write('items', item)
    this.requestSync()
    return item
  }

  async updateItem(id: string, patch: Partial<Pick<Item, 'text' | 'isChecked' | 'dueAt' | 'repeatWeeks'>>): Promise<void> {
    const item = await this.store.get('items', id)
    if (!item) return
    const next = { ...item, ...patch }
    if (patch.text !== undefined) {
      // A changed URL needs a new preview; Android fetches it on its next pass.
      next.linkUrl = findUrl(patch.text)
      if (next.linkUrl !== item.linkUrl) Object.assign(next, { linkTitle: null, linkImageUrl: null, linkFetchFailed: false })
    }
    await this.store.write('items', next)
    this.requestSync()
  }

  toggleItem(item: Item) { return this.updateItem(item.id, { isChecked: !item.isChecked }) }

  async deleteItem(id: string): Promise<Item | undefined> {
    const item = await this.store.get('items', id)
    if (!item) return
    await this.store.write('items', { ...item, deletedAt: new Date().toISOString() })
    this.requestSync()
    return item
  }

  /** Undo for deleteItem: same id, so the server revives its tombstone instead of duplicating. */
  async restoreItem(item: Item): Promise<void> {
    await this.store.write('items', { ...item, deletedAt: null })
    this.requestSync()
  }

  /**
   * Moves an item so it sits between two neighbours in the rendered list (either may be null
   * for the ends). Only the moved row is written — that's the point of fractional keys.
   */
  async moveItem(id: string, before: Item | null, after: Item | null): Promise<void> {
    const item = await this.store.get('items', id)
    if (!item) return
    await this.store.write('items', { ...item, sortKey: keyBetween(before?.sortKey ?? null, after?.sortKey ?? null) })
    this.requestSync()
  }

  // ── Schedules (the tooltip's single actions and the timer sheet) ──

  async setSchedule(id: string, dueAt: number | null, repeatWeeks: number | null): Promise<void> {
    await this.updateItem(id, { dueAt, repeatWeeks })
  }

  /**
   * "Make active now": due from `now`, repeat kept. Pass the screen's clock (see useNow) — a
   * fresher Date.now() lands after it, and the note would still count as waiting.
   */
  makeActiveNow(item: Item, now: number) { return this.setSchedule(item.id, now, item.repeatWeeks) }

  /** "Remove due": drops the date and any repeat. */
  removeDue(item: Item) { return this.setSchedule(item.id, null, null) }

  /** "Reset timer": restarts a repeating note's cycle from now. Ticking it off never does this. */
  async resetTimer(item: Item, now: number): Promise<void> {
    if (item.repeatWeeks == null) return
    await this.setSchedule(item.id, nextDueAt(now, item.repeatWeeks), item.repeatWeeks)
  }

  // ── Bulk ──

  /** Tombstones every checked note in a section (waiting ones included — they're stored). */
  async clearChecked(sectionId: string): Promise<void> {
    await this.tombstoneWhere(sectionId, (i) => i.isChecked)
  }

  async clearAll(sectionId: string): Promise<void> {
    await this.tombstoneWhere(sectionId, () => true)
  }

  private async tombstoneWhere(sectionId: string, pick: (i: Item) => boolean): Promise<void> {
    const at = new Date().toISOString()
    const rows = (await this.items(sectionId)).filter(pick).map((i) => ({ table: 'items' as const, row: { ...i, deletedAt: at } }))
    if (rows.length === 0) return
    await this.store.writeAll(rows)
    this.requestSync()
  }

  /** Rail reorder: like moveItem, only the moved section is written. */
  async moveSection(id: string, before: Section | null, after: Section | null): Promise<void> {
    const s = await this.store.get('sections', id)
    if (!s) return
    await this.store.write('sections', { ...s, sortKey: keyBetween(before?.sortKey ?? null, after?.sortKey ?? null) })
    this.requestSync()
  }

  /** To another section in the same space, at its top. */
  async moveItemToSection(id: string, sectionId: string): Promise<void> {
    const item = await this.store.get('items', id)
    if (!item || item.sectionId === sectionId) return
    const first = (await this.items(sectionId))[0]
    await this.store.write('items', { ...item, sectionId, sortKey: generateKeyBetween(null, first?.sortKey ?? null) })
    this.requestSync()
  }
}

/**
 * generateKeyBetween throws if a >= b, which two devices inserting at the same spot can produce
 * (equal keys). Fall back to placing it just after `a`; the id tie-break keeps order stable.
 */
export function keyBetween(a: string | null, b: string | null): string {
  if (a !== null && b !== null && a >= b) return generateKeyBetween(a, null)
  return generateKeyBetween(a, b)
}

const URL_RE = /https?:\/\/[^\s<>"']+/i

/**
 * Port of Android's LinkDetector.findUrl — keep the two identical, or the same note gets a link
 * card on one device and plain text on the other. Trailing punctuation is sentence punctuation.
 */
export function findUrl(text: string): string | null {
  const url = text.match(URL_RE)?.[0]?.replace(/[.,;:!?)\]}"']+$/, '')
  return url && url.length > 'https://'.length ? url : null
}
