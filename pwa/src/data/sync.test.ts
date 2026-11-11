import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import type { ItemRow, SectionRow, Space } from './model.ts'
import { findUrl, keyBetween, Notes } from './notes.ts'
import { RemoteError, type Remote } from './remote.ts'
import { Sharing, type SharingRemote } from './sharing.ts'
import { Store } from './store.ts'
import { SyncEngine } from './sync.ts'
import { isDue, isWaiting } from './schedule.ts'

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
    this.spaces.set(id, { id, name: id, kind, createdBy: users[0] ?? '' })
    for (const u of users) this.join(u, id)
  }
  private moveSection(id: string, space: string) {
    this.sections.set(id, { ...this.sections.get(id)!, space_id: space, rev: ++this.rev })
  }
  /** share_section: the section moves, same id, into a new shared space with both people in it. */
  share(sectionId: string, owner: string, invitee: string): string {
    const space = `shared-${++this.rev}`
    this.addSpace(space, 'shared', owner, invitee)
    this.moveSection(sectionId, space)
    return space
  }
  /** unshare_section: back to the owner's personal space, item revs bumped, shared space gone. */
  unshare(sectionId: string, personal: string) {
    const space = this.spaceOfSection(sectionId)!
    this.moveSection(sectionId, personal)
    for (const it of this.items.values()) {
      if (it.section_id === sectionId) this.items.set(it.id, { ...it, rev: ++this.rev })
    }
    this.spaces.delete(space)
    for (const set of this.members.values()) set.delete(space)
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
    // Like the guards: a row keeps the space the server has it in, and only the owner deletes.
    const spaceOf = (r: SectionRow) => this.server.sections.get(r.id)?.space_id ?? r.space_id
    for (const r of rows) {
      const old = this.server.sections.get(r.id)
      if (this.server.refuse.has(r.id) || !this.visible(spaceOf(r))) throw new RemoteError('42501: rls', true)
      if (r.deleted_at && !old?.deleted_at && this.server.spaces.get(spaceOf(r))?.createdBy !== this.user) {
        throw new RemoteError('42501: only the owner can delete a shared section', true)
      }
    }
    for (const r of rows) {
      const old = this.server.sections.get(r.id)
      this.server.sections.set(r.id, { ...r, space_id: spaceOf(r), rev: ++this.server.rev })
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

/** The server calls behind Sharing; "emails" are just user names here. */
class FakeSharing implements SharingRemote {
  private server: FakeServer
  private user: string
  constructor(server: FakeServer, user: string) { this.server = server; this.user = user }
  async share(sectionId: string, email: string) { return this.server.share(sectionId, this.user, email) }
  async unshare(sectionId: string) { this.server.unshare(sectionId, `${this.user}-private`) }
  async leave(spaceId: string) { this.server.leave(this.user, spaceId) }
  async people() { return [] }
}

let dbCounter = 0
async function device(server: FakeServer, user: string) {
  const store = await Store.open(`test-${++dbCounter}`)
  const engine = new SyncEngine(store, new FakeRemote(server, user))
  const notes = new Notes(store)
  const sharing = new Sharing(new FakeSharing(server, user), store, engine)
  return { store, engine, notes, sharing }
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

describe('sharing a section', () => {
  let server: FakeServer
  beforeEach(() => {
    server = new FakeServer()
    server.addSpace('alice-private', 'personal', 'alice')
    server.addSpace('bob-private', 'personal', 'bob')
  })

  /** Alice's private "Garden" with one note, synced; Bob signed in and synced too. */
  async function setup() {
    const a = await device(server, 'alice')
    const b = await device(server, 'bob')
    const garden = await a.notes.addSection('alice-private', 'Garden')
    await a.notes.addItem(garden.id, 'Seeds')
    await a.engine.sync(); await b.engine.sync()
    return { a, b, garden }
  }
  const names = async (d: { notes: Notes }) => (await d.notes.sections()).map((s) => s.name)

  it('brings the section and its notes to the other person, under the same id', async () => {
    const { a, b, garden } = await setup()
    expect(await names(b)).toEqual([])
    await a.sharing.share(garden, 'bob')
    await b.engine.sync()
    expect(await names(b)).toEqual(['Garden'])
    expect(await texts(b.notes, garden.id)).toEqual(['Seeds'])
    expect(await texts(a.notes, garden.id)).toEqual(['Seeds'])
    expect((await a.store.get('sections', garden.id))?.spaceId).toBe(server.spaceOfSection(garden.id))
  })

  it('takes an edit from a device that hasn\'t heard about the share yet', async () => {
    const { a, garden } = await setup()
    const a2 = await device(server, 'alice')
    await a2.engine.sync()
    await a.sharing.share(garden, 'bob')
    await a2.notes.updateSection(garden.id, { name: 'Garden 🌱' }) // still thinks it's private
    await a2.engine.sync()
    expect(server.sections.get(garden.id)?.name).toBe('Garden 🌱')
    expect(server.spaces.get(server.spaceOfSection(garden.id)!)?.kind).toBe('shared')
    expect((await a2.store.get('sections', garden.id))?.spaceId).toBe(server.spaceOfSection(garden.id))
  })

  it('lets both add notes', async () => {
    const { a, b, garden } = await setup()
    await a.sharing.share(garden, 'bob')
    await b.engine.sync()
    await b.notes.addItem(garden.id, 'Water')
    await b.engine.sync(); await a.engine.sync()
    expect(await texts(a.notes, garden.id)).toEqual(['Water', 'Seeds'])
  })

  it('stopping keeps everything for the owner, on every device, and removes it for the other', async () => {
    const { a, b, garden } = await setup()
    const a2 = await device(server, 'alice')
    await a.sharing.share(garden, 'bob')
    // Lots of activity since, so the note's rev is far behind a2's cursor (outside the overlap).
    server.rev += 1000
    await a.notes.addItem(garden.id, 'Spade')
    await a.engine.sync()
    await a2.engine.sync(); await b.engine.sync()
    await a.sharing.unshare(garden)
    await a2.engine.sync(); await b.engine.sync()
    for (const d of [a, a2]) {
      expect(await names(d)).toEqual(['Garden'])
      expect(await texts(d.notes, garden.id)).toEqual(['Spade', 'Seeds'])
      expect((await d.store.get('sections', garden.id))?.spaceId).toBe('alice-private')
    }
    expect(await names(b)).toEqual([])
    expect(await b.store.all('items')).toEqual([])
  })

  it('leaving removes it for the member only', async () => {
    const { a, b, garden } = await setup()
    await a.sharing.share(garden, 'bob')
    await b.engine.sync()
    await b.sharing.leave((await b.store.get('sections', garden.id))!)
    await a.engine.sync()
    expect(await names(b)).toEqual([])
    expect(await b.store.all('items')).toEqual([])
    expect(await names(a)).toEqual(['Garden'])
  })

  it('a member\'s delete is refused, and the section comes back whole', async () => {
    const { a, b, garden } = await setup()
    await a.sharing.share(garden, 'bob')
    await b.engine.sync()
    await b.notes.deleteSection(garden.id)
    await b.engine.sync()
    expect(server.sections.get(garden.id)?.deleted_at).toBeNull()
    expect(await names(b)).toEqual(['Garden'])
    expect(await texts(b.notes, garden.id)).toEqual(['Seeds'])
  })

  it('the owner\'s delete removes it for both', async () => {
    const { a, b, garden } = await setup()
    await a.sharing.share(garden, 'bob')
    await b.engine.sync()
    await a.notes.deleteSection(garden.id)
    await a.engine.sync(); await b.engine.sync()
    expect(await names(b)).toEqual([])
    expect(await names(a)).toEqual([])
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

describe('schedule actions', () => {
  it('"Make active now" is due by the clock the screen judges with, even an older one', async () => {
    const server = new FakeServer()
    server.addSpace('home', 'shared', 'alice')
    const a = await device(server, 'alice')
    const s = await a.notes.addSection('home', 'G')
    const screenNow = Date.now() - 5 * 60_000 // the screen read its clock five minutes ago
    const item = await a.notes.addItem(s.id, 'later', { dueAt: screenNow + 86_400_000 })
    expect(isWaiting(item, screenNow)).toBe(true)
    await a.notes.makeActiveNow(item, screenNow)
    const after = (await a.notes.items(s.id))[0]
    expect(isWaiting(after, screenNow)).toBe(false)
    expect(isDue(after, screenNow)).toBe(true)
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
