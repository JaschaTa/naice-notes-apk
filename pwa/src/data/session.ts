// Everything that lives for as long as someone is signed in: their local store, the sync engine,
// and the triggers that keep it running.
import type { SupabaseClient } from '@supabase/supabase-js'
import { Notes } from './notes.ts'
import { SupabaseRemote } from './remote.ts'
import { Store } from './store.ts'
import { SyncEngine } from './sync.ts'

const PERIODIC_MS = 60_000
const LOCAL_WRITE_DEBOUNCE_MS = 400

export interface Session {
  userId: string
  email: string
  store: Store
  engine: SyncEngine
  notes: Notes
  close(): void
}

export async function startSession(sb: SupabaseClient, userId: string, email: string): Promise<Session> {
  // One database per account, so signing in as someone else on the same device never mixes data.
  const store = await Store.open(`naice-notes-${userId}`)
  const engine = new SyncEngine(store, new SupabaseRemote(sb))

  let debounce: ReturnType<typeof setTimeout> | undefined
  const soon = () => {
    clearTimeout(debounce)
    debounce = setTimeout(() => void engine.sync(), LOCAL_WRITE_DEBOUNCE_MS)
  }
  const now = () => void engine.sync()
  const notes = new Notes(store, soon)

  // Realtime only says "something changed"; the pull decides what. Applying payloads directly
  // would be a second code path that could disagree with the first.
  const channel = sb.channel('naice-sync')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'sections' }, soon)
    .on('postgres_changes', { event: '*', schema: 'public', table: 'items' }, soon)
    .subscribe()

  const onVisible = () => { if (document.visibilityState === 'visible') now() }
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('online', now)
  const timer = setInterval(() => { if (document.visibilityState === 'visible') now() }, PERIODIC_MS)
  now()

  return {
    userId, email, store, engine, notes,
    close() {
      clearTimeout(debounce)
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('online', now)
      void sb.removeChannel(channel)
      store.close()
    },
  }
}
