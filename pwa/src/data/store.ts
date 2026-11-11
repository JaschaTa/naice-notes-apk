// IndexedDB mirror of everything the signed-in user can see, plus the outbox (rows with
// dirty = 1). The server is the source of truth; this exists so the app opens instantly and
// keeps working offline. Safe to wipe — the next sync refills it.
import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { Item, Section, Space } from './model.ts'

interface NotesDB extends DBSchema {
  sections: { key: string; value: Section; indexes: { bySpace: string; byDirty: number } }
  items: { key: string; value: Item; indexes: { bySection: string; byDirty: number } }
  spaces: { key: string; value: Space }
  meta: { key: string; value: unknown }
}

export type Table = 'sections' | 'items'
type Row<T extends Table> = T extends 'sections' ? Section : Item

export class Store {
  private listeners = new Set<() => void>()
  private db: IDBPDatabase<NotesDB>
  private constructor(db: IDBPDatabase<NotesDB>) { this.db = db }

  static async open(name = 'naice-notes'): Promise<Store> {
    const db = await openDB<NotesDB>(name, 1, {
      upgrade(db) {
        const sections = db.createObjectStore('sections', { keyPath: 'id' })
        sections.createIndex('bySpace', 'spaceId')
        sections.createIndex('byDirty', 'dirty')
        const items = db.createObjectStore('items', { keyPath: 'id' })
        items.createIndex('bySection', 'sectionId')
        items.createIndex('byDirty', 'dirty')
        db.createObjectStore('spaces', { keyPath: 'id' })
        db.createObjectStore('meta')
      },
    })
    return new Store(db)
  }

  close() { this.db.close() }

  /** Called after every change, local or pulled. Returns an unsubscribe function. */
  subscribe(fn: () => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
  private notify() { this.listeners.forEach((fn) => fn()) }

  // ── Reads ──

  get<T extends Table>(table: T, id: string): Promise<Row<T> | undefined> {
    return this.db.get(table, id) as Promise<Row<T> | undefined>
  }
  all<T extends Table>(table: T): Promise<Row<T>[]> {
    return this.db.getAll(table) as Promise<Row<T>[]>
  }
  itemsInSection(sectionId: string): Promise<Item[]> {
    return this.db.getAllFromIndex('items', 'bySection', sectionId)
  }
  dirty<T extends Table>(table: T): Promise<Row<T>[]> {
    return this.db.getAllFromIndex(table as Table, 'byDirty' as never, 1 as never) as Promise<Row<T>[]>
  }
  spaces(): Promise<Space[]> { return this.db.getAll('spaces') }

  // ── Local writes: always mark dirty and bump the version ──

  async write<T extends Table>(table: T, row: Row<T>): Promise<void> {
    const current = await this.db.get(table, row.id)
    await this.db.put(table, { ...row, dirty: 1, version: (current?.version ?? 0) + 1 } as never)
    this.notify()
  }

  /** Several local writes in one transaction, one notification. */
  async writeAll(rows: { table: Table; row: Section | Item }[]): Promise<void> {
    const tx = this.db.transaction(['sections', 'items'], 'readwrite')
    for (const { table, row } of rows) {
      const store = tx.objectStore(table)
      const current = await store.get(row.id)
      await store.put({ ...row, dirty: 1, version: (current?.version ?? 0) + 1 } as never)
    }
    await tx.done
    this.notify()
  }

  // ── Sync-side writes: never mark dirty ──

  /**
   * Clears `dirty` after a successful push, but only if nothing was written since `version`
   * was read. A pushed tombstone is removed outright: the server has it now.
   */
  async markPushed(table: Table, id: string, version: number): Promise<void> {
    const tx = this.db.transaction(table, 'readwrite')
    const row = await tx.store.get(id)
    if (row && row.version === version) {
      if (row.deletedAt) await tx.store.delete(id)
      else await tx.store.put({ ...row, dirty: 0 } as never)
    }
    await tx.done
  }

  /**
   * Applies rows from the server. A row with unpushed local changes is left alone — the local
   * change is newer as far as this device knows, and the next push makes it the server's too.
   */
  async applyPulled(table: Table, rows: (Section | Item)[]): Promise<number> {
    if (rows.length === 0) return 0
    let applied = 0
    const tx = this.db.transaction(table, 'readwrite')
    for (const row of rows) {
      const local = await tx.store.get(row.id)
      if (local?.dirty) continue
      if (row.deletedAt) {
        if (local) { await tx.store.delete(row.id); applied++ }
      } else {
        await tx.store.put({ ...row, dirty: 0, version: local?.version ?? 0 } as never)
        applied++
      }
    }
    await tx.done
    if (applied) this.notify()
    return applied
  }

  /** Drops a local row outright — used when the server permanently refuses it. */
  async discard(table: Table, id: string): Promise<void> {
    await this.db.delete(table, id)
    this.notify()
  }

  /** Removes every section (and its items) of spaces the user no longer belongs to. */
  async removeSpaces(spaceIds: string[]): Promise<void> {
    if (spaceIds.length === 0) return
    const tx = this.db.transaction(['sections', 'items', 'spaces'], 'readwrite')
    for (const spaceId of spaceIds) {
      const sections = await tx.objectStore('sections').index('bySpace').getAll(spaceId)
      for (const s of sections) {
        const items = await tx.objectStore('items').index('bySection').getAllKeys(s.id)
        for (const key of items) await tx.objectStore('items').delete(key)
        await tx.objectStore('sections').delete(s.id)
      }
      await tx.objectStore('spaces').delete(spaceId)
    }
    await tx.done
    this.notify()
  }

  async setSpaces(spaces: Space[]): Promise<void> {
    const tx = this.db.transaction('spaces', 'readwrite')
    await tx.store.clear()
    for (const s of spaces) await tx.store.put(s)
    await tx.done
    this.notify()
  }

  /** Forgets all synced content, keeping unpushed local changes so they still go out. */
  async clearSynced(): Promise<void> {
    const tx = this.db.transaction(['sections', 'items', 'spaces'], 'readwrite')
    for (const table of ['sections', 'items'] as const) {
      const store = tx.objectStore(table)
      for (const row of await store.getAll()) if (!row.dirty) await store.delete(row.id)
    }
    await tx.objectStore('spaces').clear()
    await tx.done
    this.notify()
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    return (await this.db.get('meta', key)) as T | undefined
  }
  async setMeta(key: string, value: unknown): Promise<void> {
    await this.db.put('meta', value, key)
  }

  /** Everything, for sign-out. */
  async wipe(): Promise<void> {
    const tx = this.db.transaction(['sections', 'items', 'spaces', 'meta'], 'readwrite')
    await Promise.all([
      tx.objectStore('sections').clear(), tx.objectStore('items').clear(),
      tx.objectStore('spaces').clear(), tx.objectStore('meta').clear(),
    ])
    await tx.done
    this.notify()
  }
}
