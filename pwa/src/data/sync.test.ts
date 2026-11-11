import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import type { ItemRow, SectionRow, Space } from './model.ts'
import { findUrl, keyBetween, Notes } from './notes.ts'
import { RemoteError, type Remote } from './remote.ts'
import { Store } from './store.ts'
import { SyncEngine } from './sync.ts'

// ── An in-memory server with the same rules as the Postgres schema ──

class FakeServer {
  rev = 0
  horizon = 0
  spaces = new Map<string, Space>()
  members = new Map<string, Set<string>>() // user → space ids
  sections = new Map<string, SectionRow>()
  items = new Map<string, ItemRow>()
  offline = false
  refuse = new Set<string>() // row ids the server rejects permanently
  onUpsert?: (table: 'sections' | 'items') => Promise<void>

  addSpace(id: string, kind: Space['kind'], ...users: string[]) {
    this.spaces.set(id, { id, name: id, kind })
    for (const u of users) this.join(u, id)
  }
  join(user: string, space: string) {
    if (!this.members.has(user)) this.members.set(user, new Set())
    this.members.get(user)!.add(space)
  }
  leave(user: string, space: string) { this.members.get(user)?.delete(space) }
  spaceOfSection(id: string) { return this.sections.get(id)?.space_id }
}

class FakeRemote implements Remote {
  private server: FakeServer
  private user: string
  constructor(server: FakeServer, user: string) {
    this.server = server
    this.user = user
  }
  private check() { if (this.server.offline) throw new TypeError('Failed to fetch') }
  private visible(space?: string) { return !!space && !!this.server.members.get(this.user)?.has(space) }

  async listSpaces() {
    this.check()
    return [...this.server.spaces.values()].filter((s) => this.visible(s.id))
  }
  async horizon() { this.check(); return this.server.horizon }
  async sectionsSince(since: number) {
    this.check()
    return [...this.server.sections.values()].filter((r) => r.rev! > since && this.visible(r.space_id)).sort((a, b) => a.rev! - b.rev!)
  }
  async itemsSince(since: number) {
    this.check()
    return [...this.server.items.values()]
      .filter((r) => r.rev! > since && this.visible(this.server.spaceOfSection(r.section_id))).sort((a, b) => a.rev! - b.rev!)
  }
  async sectionsInSpaces(ids: string[]) { return (await this.sectionsSince(0)).filter((r) => ids.includes(r.space_id)) }
  async itemsInSpaces(ids: string[]) {
    return (await this.itemsSince(0)).filter((r) => ids.includes(this.server.spaceOfSection(r.section_id)!))
  }
  async upsertSections(rows: SectionRow[]) {
    this.check()
    await this.server.onUpsert?.('sections')
    for (const r of rows) {
      if (this.server.refuse.has(r.id) || !this.visible(r.space_id)) throw new RemoteError('42501: rls', true)
    }
    for (const r of rows) {
      const old = this.server.sections.get(r.id)
      this.server.sections.set(r.id, { ...r, rev: ++this.server.rev })
      if (r.deleted_at && !old?.deleted_at) {
        // Server-side cascade, like cascade_section_tombstone().
        for (const item of this.server.items.values()) {
          if (item.section_id === r.id && !item.deleted_at) {
            this.server.items.set(item.id, { ...item, deleted_at: r.deleted_at, rev: ++this.server.rev })
          }
        }
      }
    }
  }
  async upsertItems(rows: ItemRow[]) {
    this.check()
    await this.server.onUpsert?.('items')
    for (const r of rows) {
      if (this.server.refuse.has(r.id) || !this.visible(this.server.spaceOfSection(r.section_id))) {
        throw new RemoteError('42501: rls', true)
      }
    }
    for (const r of rows) {
      const old = this.server.items.get(r.id)
      this.server.items.set(r.id, { ...r, created_by: old?.created_by ?? this.user, rev: ++this.server.rev })
    }
  }
}

let dbCounter = 0
async function device(server: FakeServer, user: string) {
  const store = await Store.open(`test-${++dbCounter}`)
  const engine = new SyncEngine(store, new FakeRemote(server, user))
  const notes = new Notes(store)
  return { store, engine, notes }
}

const texts = async (notes: Notes, sectionId: string) => (await notes.items(sectionId)).map((i) => i.text)

describe('sync between two devices', () => {
  let server: FakeServer
  beforeEach(() => {
    server = new FakeServer()
    server.addSpace('home', 'shared', 'alice', 'bob')
    server.addSpace('alice-private', 'personal', 'alice')
  })

  it('carries a new section and note from one device to the other', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const s = await a.notes.addSection('home', 'Groceries')
    await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync()
    await b.engine.sync()
    expect((await b.notes.sections()).map((x) => x.name)).toEqual(['Groceries'])
    expect(await texts(b.notes, s.id)).toEqual(['Milk'])
    expect((await b.notes.items(s.id))[0].createdBy).toBe('alice')
  })

  it('keeps private spaces private', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    await a.notes.addSection('alice-private', 'Diary')
    await a.engine.sync()
    await b.engine.sync()
    expect(await b.notes.sections()).toEqual([])
  })

  it('clears the outbox after a successful push', async () => {
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync()
    expect(await a.store.dirty('items')).toEqual([])
    expect(await a.store.dirty('sections')).toEqual([])
  })

  it('lets the last pushed edit win', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const s = await a.notes.addSection('home', 'G')
    const item = await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync()
    await b.engine.sync()

    await a.notes.updateItem(item.id, { text: 'Oat milk' })
    await b.notes.updateItem(item.id, { text: 'Soy milk' })
    await a.engine.sync()
    await b.engine.sync() // b pushes before it pulls, so its edit lands last and wins
    expect(await texts(b.notes, s.id)).toEqual(['Soy milk'])
    await a.engine.sync()
    expect(await texts(a.notes, s.id)).toEqual(['Soy milk'])
  })

  it('does not lose an edit made while a push is in flight', async () => {
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    const item = await a.notes.addItem(s.id, 'Milk')
    let once = true
    server.onUpsert = async (table) => {
      if (table === 'items' && once) { once = false; await a.notes.updateItem(item.id, { text: 'Oat milk' }) }
    }
    await a.engine.sync()
    server.onUpsert = undefined
    expect((await a.store.dirty('items')).map((i) => i.text)).toEqual(['Oat milk'])
    await a.engine.sync()
    expect(server.items.get(item.id)?.text).toBe('Oat milk')
  })

  it('propagates deletes, and undo revives the same note', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const s = await a.notes.addSection('home', 'G')
    const item = await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync(); await b.engine.sync()

    const deleted = await a.notes.deleteItem(item.id)
    await a.engine.sync(); await b.engine.sync()
    expect(await texts(b.notes, s.id)).toEqual([])
    expect(await a.store.get('items', item.id)).toBeUndefined() // pushed tombstone is dropped locally

    await a.notes.restoreItem(deleted!)
    await a.engine.sync(); await b.engine.sync()
    expect(await texts(b.notes, s.id)).toEqual(['Milk'])
    expect(server.items.size).toBe(1)
  })

  it('removes a deleted section and its notes on the other device', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const s = await a.notes.addSection('home', 'G')
    await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync(); await b.engine.sync()
    await a.notes.deleteSection(s.id)
    await a.engine.sync(); await b.engine.sync()
    expect(await b.notes.sections()).toEqual([])
    expect(await b.store.all('items')).toEqual([])
  })

  it('queues changes while offline and sends them when back', async () => {
    const a = await device(server, 'alice')
    const statuses: string[] = []
    a.engine.onStatus((st) => statuses.push(st.state))
    server.offline = true
    const s = await a.notes.addSection('home', 'G')
    for (const t of ['one', 'two', 'three']) await a.notes.addItem(s.id, t)
    await a.engine.sync()
    expect(statuses.at(-1)).toBe('offline')
    expect((await a.store.dirty('items')).length).toBe(3)
    server.offline = false
    await a.engine.sync()
    expect(statuses.at(-1)).toBe('idle')
    expect(server.items.size).toBe(3)
    expect(await texts(a.notes, s.id)).toEqual(['three', 'two', 'one'])
  })

  it('reads a space in full when joining it after the cursor moved on', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    server.addSpace('work', 'shared', 'alice')
    const s = await a.notes.addSection('work', 'Projects')
    await a.notes.addItem(s.id, 'Ship it')
    await a.engine.sync()
    // Bob syncs a lot of unrelated activity first, so his cursor is well past those rows.
    const h = await b.notes.addSection('home', 'H')
    for (let i = 0; i < REV_SPAM; i++) await b.notes.addItem(h.id, `x${i}`)
    await b.engine.sync()
    server.join('bob', 'work')
    await b.engine.sync()
    expect(await texts(b.notes, s.id)).toEqual(['Ship it'])
  })

  it('forgets a space it lost access to', async () => {
    const b = await device(server, 'bob')
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync(); await b.engine.sync()
    server.leave('bob', 'home')
    await b.engine.sync()
    expect(await b.notes.sections()).toEqual([])
    expect(await b.store.all('items')).toEqual([])
  })

  it('does a full resync when tombstones were purged past its cursor', async () => {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const s = await a.notes.addSection('home', 'G')
    const item = await a.notes.addItem(s.id, 'Milk')
    await a.engine.sync(); await b.engine.sync()
    // The note is deleted and the tombstone purged while Bob is away.
    server.items.delete(item.id)
    server.horizon = server.rev + 1000
    await b.engine.sync()
    expect(await texts(b.notes, s.id)).toEqual([])
  })

  it('drops a row the server refuses without blocking the rest', async () => {
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    await a.engine.sync()
    const bad = await a.notes.addItem(s.id, 'bad')
    await a.notes.addItem(s.id, 'good')
    server.refuse.add(bad.id)
    await a.engine.sync()
    expect([...server.items.values()].map((i) => i.text)).toEqual(['good'])
    expect(await texts(a.notes, s.id)).toEqual(['good'])
    expect(await a.store.dirty('items')).toEqual([])
  })

  it('folds a burst of sync requests into at most two runs', async () => {
    const a = await device(server, 'alice')
    let pulls = 0
    const orig = server.spaces
    server.spaces = new Proxy(orig, {
      get(t, p) { if (p === 'values') pulls++; const v = Reflect.get(t, p); return typeof v === 'function' ? v.bind(t) : v },
    })
    await Promise.all([a.engine.sync(), a.engine.sync(), a.engine.sync(), a.engine.sync()])
    expect(pulls).toBe(2)
  })
})

const REV_SPAM = 250 // > REV_OVERLAP, so the joined space's rows fall outside the overlap window

describe('ordering', () => {
  it('puts new notes on top, in the order they were added', async () => {
    const server = new FakeServer()
    server.addSpace('home', 'shared', 'alice')
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    for (const t of ['a', 'b', 'c']) await a.notes.addItem(s.id, t)
    expect(await texts(a.notes, s.id)).toEqual(['c', 'b', 'a'])
  })

  it('moves a note between two others by rewriting only that note', async () => {
    const server = new FakeServer()
    server.addSpace('home', 'shared', 'alice')
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    for (const t of ['a', 'b', 'c']) await a.notes.addItem(s.id, t)
    await a.engine.sync()
    const [c, b, aa] = await a.notes.items(s.id)
    await a.notes.moveItem(aa.id, c, b)
    expect(await texts(a.notes, s.id)).toEqual(['c', 'a', 'b'])
    expect((await a.store.dirty('items')).map((i) => i.text)).toEqual(['a'])
  })

  it('survives equal keys from two devices inserting at the same spot', () => {
    expect(keyBetween('a0', 'a0') > 'a0').toBe(true)
    expect(keyBetween('a1', 'a0') > 'a1').toBe(true)
  })
})

describe('findUrl (port of Android LinkDetector)', () => {
  it.each([
    ['see https://example.com/x.', 'https://example.com/x'],
    ['(https://example.com/a)', 'https://example.com/a'],
    ['<https://example.com/b>', 'https://example.com/b'],
    ['HTTP://EXAMPLE.COM', 'HTTP://EXAMPLE.COM'],
    ['just https://', null],
    ['no link here', null],
  ])('%s', (text, expected) => expect(findUrl(text)).toBe(expected))
})
