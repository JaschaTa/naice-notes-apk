// Development-only: a signed-in session backed by a fake server and seeded sample data, so the
// screens can be looked at without signing in. Loaded via a dynamic import behind
// import.meta.env.DEV, so production builds don't contain it. Open /?demo with `npm run dev`.
import { generateNKeysBetween } from 'fractional-indexing'
import type { Item, Section } from '../data/model.ts'
import { Notes } from '../data/notes.ts'
import type { Remote } from '../data/remote.ts'
import type { Session } from '../data/session.ts'
import { Sharing } from '../data/sharing.ts'
import { startOfDayIn } from '../data/schedule.ts'
import { Store } from '../data/store.ts'
import { SyncEngine } from '../data/sync.ts'

const SPACE = 'demo-personal'
const SHARED_BY_ME = 'demo-shared-by-me'
const SHARED_WITH_ME = 'demo-shared-with-me'

const nullRemote: Remote = {
  listSpaces: async () => [
    { id: SPACE, name: 'Personal', kind: 'personal', createdBy: 'demo' },
    { id: SHARED_BY_ME, name: 'Shopping', kind: 'shared', createdBy: 'demo' },
    { id: SHARED_WITH_ME, name: 'Household', kind: 'shared', createdBy: 'partner' },
  ],
  horizon: async () => 0,
  sectionsSince: async () => [], itemsSince: async () => [],
  sectionsInSpaces: async () => [], itemsInSpaces: async () => [],
  upsertSections: async () => {}, upsertItems: async () => {},
}

export async function startDemoSession(): Promise<Session> {
  const store = await Store.open('naice-notes-demo')
  if ((await store.all('sections')).length === 0) await seed(store)
  const engine = new SyncEngine(store, nullRemote)
  const notes = new Notes(store, () => void engine.sync())
  void engine.sync()
  const unavailable = async (): Promise<never> => { throw new Error('Sharing isn\'t available in the demo.') }
  const sharing = new Sharing({ share: unavailable, unshare: unavailable, leave: unavailable, people: async () => [
    { spaceId: SHARED_BY_ME, email: 'demo@example.com', role: 'owner', joined: true },
    { spaceId: SHARED_BY_ME, email: 'partner@example.com', role: 'member', joined: true },
    { spaceId: SHARED_BY_ME, email: 'friend@example.com', role: 'member', joined: false },
    { spaceId: SHARED_WITH_ME, email: 'partner@example.com', role: 'owner', joined: true },
    { spaceId: SHARED_WITH_ME, email: 'demo@example.com', role: 'member', joined: true },
  ] }, store, engine)
  return { userId: 'demo', email: 'demo@example.com', store, engine, notes, sharing, close: () => store.close() }
}

async function seed(store: Store) {
  const now = Date.now()
  const day = 86_400_000
  const sec = (name: string, emoji: string | null, color: number, sortKey: string, spaceId = SPACE): Section => ({
    id: crypto.randomUUID(), spaceId, name, emoji, color: color | 0, sortKey, createdAt: now, deletedAt: null, rev: null, dirty: 1, version: 1,
  })
  const [k1, k2, k3, k4] = generateNKeysBetween(null, null, 4)
  const shopping = sec('Shopping', '🛒', 0xff4caf50, k1, SHARED_BY_ME)
  const house = sec('Household', '🏠', 0xff2196f3, k2, SHARED_WITH_ME)
  const work = sec('Work', null, 0xffe91e63, k3)
  const reading = sec('Reading', '📚', 0xff9c27b0, k4)
  const item = (sectionId: string, text: string, sortKey: string, extra: Partial<Item> = {}): Item => ({
    id: crypto.randomUUID(), sectionId, text, isChecked: false, sortKey, createdAt: now, createdBy: null,
    linkUrl: null, linkTitle: null, linkImageUrl: null, linkFetchFailed: false, dueAt: null, repeatWeeks: null,
    deletedAt: null, rev: null, dirty: 1, version: 1, ...extra,
  })
  const keys = generateNKeysBetween(null, null, 9)
  const url = 'https://www.kaufland.de/product/12345/mozzarella-di-bufala-campana/'
  const rows: Item[] = [
    item(shopping.id, 'olive oil', keys[0]),
    item(shopping.id, 'renew contents insurance', keys[1], { dueAt: startOfDayIn(now, 0) }),
    item(shopping.id, 'fresh basil leaves', keys[2]),
    item(shopping.id, 'water the balcony plants', keys[3], { dueAt: now - 3 * day, repeatWeeks: 1 }),
    item(shopping.id, url, keys[4], { linkUrl: url, linkTitle: 'Mozzarella di Bufala Campana DOP 125 g' }),
    item(shopping.id, '2 cups flour', keys[5], { isChecked: true }),
    item(shopping.id, 'book the winter service', keys[6], { dueAt: startOfDayIn(now, 12) }),
    item(shopping.id, 'descale the coffee machine', keys[7], { dueAt: startOfDayIn(now, 21), repeatWeeks: 4 }),
    item(house.id, 'change the bed sheets', keys[0], { dueAt: now - day, repeatWeeks: 2 }),
    item(house.id, 'call the plumber', keys[1]),
    item(work.id, 'prepare the quarterly review', keys[0]),
    item(reading.id, 'The Pragmatic Programmer', keys[0], { isChecked: true }),
  ]
  await store.writeAll([...[shopping, house, work, reading].map((row) => ({ table: 'sections' as const, row })), ...rows.map((row) => ({ table: 'items' as const, row }))])
}
