// Deliberately plain: enough to exercise sync against the live backend. The real screens
// (rail, two-block list, timers, drag) replace this in the next step.
import { useEffect, useState } from 'preact/hooks'
import { cssColor } from '../data/notes.ts'
import type { Session } from '../data/session.ts'
import type { SyncStatus } from '../data/sync.ts'
import { useLive } from './useLive.ts'

export function Home({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const { store, notes, engine } = session
  const spaces = useLive(store, () => store.spaces(), [])
  const sections = useLive(store, () => notes.sections(), [])
  const [selected, setSelected] = useState<string | null>(null)
  const sectionId = selected && sections?.some((s) => s.id === selected) ? selected : sections?.[0]?.id ?? null
  const items = useLive(store, () => (sectionId ? notes.items(sectionId) : Promise.resolve([])), [sectionId])
  const [status, setStatus] = useState<SyncStatus>({ state: 'idle', lastSyncedAt: null })
  useEffect(() => engine.onStatus(setStatus), [engine])
  const [draft, setDraft] = useState('')

  const personal = spaces?.find((s) => s.kind === 'personal')

  async function addSection() {
    const name = prompt('Section name')?.trim()
    if (name && personal) setSelected((await notes.addSection(personal.id, name)).id)
  }

  async function add(e: Event) {
    e.preventDefault()
    if (!sectionId || !draft.trim()) return
    await notes.addItem(sectionId, draft.trim())
    setDraft('')
  }

  return (
    <div class="home">
      <header>
        <nav class="tabs">
          {sections?.map((s) => (
            <button key={s.id} class={s.id === sectionId ? 'tab active' : 'tab'}
              style={{ '--c': cssColor(s.color) }} onClick={() => setSelected(s.id)}>
              {s.emoji ?? s.name.slice(0, 1).toUpperCase()} {s.name}
            </button>
          ))}
          <button class="tab" onClick={addSection} disabled={!personal}>＋</button>
        </nav>
      </header>

      {sectionId ? (
        <>
          <form class="composer" onSubmit={add}>
            <input placeholder="Add a note" value={draft} onInput={(e) => setDraft(e.currentTarget.value)} />
          </form>
          <ul class="items">
            {items?.map((i) => (
              <li key={i.id} class={i.isChecked ? 'done' : ''}>
                <input type="checkbox" checked={i.isChecked} onChange={() => notes.toggleItem(i)} />
                <span>{i.linkTitle ?? i.text}</span>
                <button class="link" aria-label="Delete" onClick={() => notes.deleteItem(i.id)}>✕</button>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p class="muted center">{spaces === undefined ? 'Loading…' : 'No sections yet. Tap ＋ to add one.'}</p>
      )}

      <footer>
        <span>{session.email} · <button class="link" onClick={onSignOut}>Sign out</button></span>
        <span class={`status ${status.state}`}>
          {status.state === 'syncing' ? 'syncing…' : status.state === 'offline' ? 'offline — changes are saved and will sync' : 'synced'}
        </span>
        <span>v{__APP_VERSION__}</span>
      </footer>
    </div>
  )
}
