// Push the outbox, then pull everything newer than the cursor. Same contract as Android (see
// supabase/README.md): last write wins per row, the server's rev orders everything, deletes are
// tombstones.
import {
  itemFromRow, itemToRow, sectionFromRow, sectionToRow,
  type Item, type ItemRow, type Section, type SectionRow,
} from './model.ts'
import { RemoteError, type Remote } from './remote.ts'
import type { Store, Table } from './store.ts'

/**
 * How far behind the cursor each pull re-reads. Revs come from a sequence that hands out
 * numbers before commit, so a slow transaction can become visible *after* a faster one with a
 * higher rev. Re-reading a window behind the cursor picks those up; applying a row twice is
 * harmless.
 */
export const REV_OVERLAP = 200

const CURSOR = 'cursor'

export type SyncStatus =
  | { state: 'idle'; lastSyncedAt: number | null }
  | { state: 'syncing' }
  | { state: 'offline'; error: string }

export class SyncEngine {
  private running: Promise<void> | null = null
  private again = false
  private status: SyncStatus = { state: 'idle', lastSyncedAt: null }
  private statusListeners = new Set<(s: SyncStatus) => void>()

  private store: Store
  private remote: Remote
  constructor(store: Store, remote: Remote) {
    this.store = store
    this.remote = remote
  }

  onStatus(fn: (s: SyncStatus) => void): () => void {
    this.statusListeners.add(fn)
    fn(this.status)
    return () => this.statusListeners.delete(fn)
  }
  private setStatus(s: SyncStatus) {
    this.status = s
    this.statusListeners.forEach((fn) => fn(s))
  }

  /**
   * Runs one push+pull. Calls while one is running are folded into a single follow-up run, so
   * a burst of triggers (several edits, a realtime event, coming back online) costs at most two.
   */
  sync(): Promise<void> {
    if (this.running) {
      this.again = true
      return this.running
    }
    this.running = (async () => {
      try {
        do {
          this.again = false
          this.setStatus({ state: 'syncing' })
          await this.push()
          await this.pull()
        } while (this.again)
        this.setStatus({ state: 'idle', lastSyncedAt: Date.now() })
      } catch (e) {
        this.setStatus({ state: 'offline', error: e instanceof Error ? e.message : String(e) })
      } finally {
        this.running = null
      }
    })()
    return this.running
  }

  // ── Push ──

  private async push(): Promise<void> {
    // Sections first: an item's section must exist on the server before the item can.
    await this.pushTable('sections', await this.store.dirty('sections'),
      (rows) => this.remote.upsertSections(rows as SectionRow[]), sectionToRow)
    await this.pushTable('items', await this.store.dirty('items'),
      (rows) => this.remote.upsertItems(rows as ItemRow[]), itemToRow)
  }

  private async pushTable<R extends Section | Item>(
    table: Table, rows: R[], send: (rows: object[]) => Promise<void>, toRow: (r: R) => object,
  ): Promise<void> {
    if (rows.length === 0) return
    try {
      await send(rows.map(toRow))
      for (const r of rows) await this.store.markPushed(table, r.id, r.version)
      return
    } catch (e) {
      if (!(e instanceof RemoteError) || !e.permanent) throw e
    }
    // The batch was refused. Find the culprit row by row so one bad row can't hold up the rest.
    for (const r of rows) {
      try {
        await send([toRow(r)])
        await this.store.markPushed(table, r.id, r.version)
      } catch (e) {
        if (!(e instanceof RemoteError) || !e.permanent) throw e
        // The server will never take this row (lost access, moved across spaces, …). Drop the
        // local version and re-read everything so the server's truth comes back.
        console.warn(`sync: server refused ${table} ${r.id}: ${e.message}`)
        await this.store.discard(table, r.id)
        await this.store.setMeta(CURSOR, 0)
      }
    }
  }

  // ── Pull ──

  private async pull(): Promise<void> {
    const spaces = await this.remote.listSpaces()
    const known = new Set((await this.store.spaces()).map((s) => s.id))
    const current = new Set(spaces.map((s) => s.id))

    // Lost access: nothing will ever tell us those rows changed again, so forget them now.
    await this.store.removeSpaces([...known].filter((id) => !current.has(id)))

    let cursor = (await this.store.getMeta<number>(CURSOR)) ?? 0
    if (cursor > 0 && cursor < (await this.remote.horizon())) {
      // Tombstones we never saw have been purged; only a full re-read is trustworthy.
      await this.store.clearSynced()
      cursor = 0
    }

    const since = Math.max(0, cursor - REV_OVERLAP)
    const sections = await this.remote.sectionsSince(since)
    const items = await this.remote.itemsSince(since)

    // A space joined after our cursor moved on: its existing rows have old revs that a
    // rev-based pull will never return, so read those spaces in full.
    const joined = cursor === 0 ? [] : spaces.map((s) => s.id).filter((id) => !known.has(id))
    if (joined.length > 0) {
      sections.push(...(await this.remote.sectionsInSpaces(joined)))
      items.push(...(await this.remote.itemsInSpaces(joined)))
    }

    await this.store.applyPulled('sections', sections.map(sectionFromRow))
    await this.store.applyPulled('items', items.map(itemFromRow))
    await this.store.setSpaces(spaces)

    // reduce, not Math.max(...rows): spreading a first sync's worth of rows overflows the stack.
    const maxRev = [...sections, ...items].reduce((max, r) => Math.max(max, Number(r.rev ?? 0)), cursor)
    await this.store.setMeta(CURSOR, maxRev)
  }
}
