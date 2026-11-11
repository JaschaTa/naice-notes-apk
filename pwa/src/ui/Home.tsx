import { useEffect, useState } from 'preact/hooks'
import type { Item, Section } from '../data/model.ts'
import { bySortKey } from '../data/model.ts'
import { cssColor } from '../data/notes.ts'
import { countsBySection, totalsBySection } from '../data/schedule.ts'
import type { Session } from '../data/session.ts'
import type { SyncStatus } from '../data/sync.ts'
import { MoreVert } from './icons.tsx'
import { Composer } from './Composer.tsx'
import { ItemList, type ItemActions } from './ItemList.tsx'
import { Rail } from './Rail.tsx'
import { ScheduleSheet } from './ScheduleSheet.tsx'
import { ClearSheet, MoveSheet, SectionSheet } from './SectionSheets.tsx'
import { ConfirmSheet, MenuSheet, Sheet } from './Sheet.tsx'
import { Snackbar, type SnackbarMessage } from './Snackbar.tsx'
import { Tile } from './Tile.tsx'
import { useLive } from './useLive.ts'
import { useNow } from './useNow.ts'

type Open =
  | { kind: 'sectionMenu' } | { kind: 'newSection' } | { kind: 'editSection' } | { kind: 'clear' } | { kind: 'deleteSection' }
  | { kind: 'itemMenu'; item: Item } | { kind: 'timer'; item: Item } | { kind: 'move'; item: Item } | { kind: 'account' }

const pref = {
  get: (k: string) => localStorage.getItem(`naice.${k}`),
  set: (k: string, v: string | null) => (v == null ? localStorage.removeItem(`naice.${k}`) : localStorage.setItem(`naice.${k}`, v)),
}

export function Home({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const { store, notes, engine } = session
  const now = useNow()
  const spaces = useLive(store, () => store.spaces(), [])
  const sections = useLive(store, () => notes.sections(), []) ?? []
  // Every live note, once: drives the rail badges, the header counts and the clear totals.
  const allItems = useLive(store, async () => (await store.all('items')).filter((i) => !i.deletedAt), []) ?? []

  const [selected, setSelected] = useState<string | null>(() => pref.get('section'))
  const section = sections.find((s) => s.id === selected) ?? sections[0] ?? null
  const items = allItems.filter((i) => i.sectionId === section?.id).sort(bySortKey)
  const [collapsed, setCollapsed] = useState(() => pref.get('railCollapsed') === '1')
  const [open, setOpen] = useState<Open | null>(null)
  const [snack, setSnack] = useState<SnackbarMessage | null>(null)
  const [status, setStatus] = useState<SyncStatus>({ state: 'idle', lastSyncedAt: null })
  useEffect(() => engine.onStatus(setStatus), [engine])

  const counts = countsBySection(allItems, now)
  const totals = totalsBySection(allItems)
  const personal = spaces?.find((s) => s.kind === 'personal')
  const close = () => setOpen(null)

  function select(id: string) { setSelected(id); pref.set('section', id) }
  function toggleRail() { const next = !collapsed; setCollapsed(next); pref.set('railCollapsed', next ? '1' : null) }
  const toast = (text: string, action?: SnackbarMessage['action']) => setSnack({ id: Date.now(), text, action })

  const actions: ItemActions = {
    toggle: (item) => void notes.toggleItem(item),
    editText: (item, text) => void notes.updateItem(item.id, { text }),
    delete: async (item) => {
      const removed = await notes.deleteItem(item.id)
      // Undo restores the same note, sort key and all, so it returns to where it was.
      if (removed) toast(`Deleted "${(item.text || 'Item').slice(0, 30)}"`, { label: 'Undo', run: () => void notes.restoreItem(removed) })
    },
    reorder: (id, before, after) => void notes.moveItem(id, before, after),
    openMenu: (item) => setOpen({ kind: 'itemMenu', item }),
    makeActiveNow: (item) => void notes.makeActiveNow(item),
    removeDue: (item) => void notes.removeDue(item),
    resetTimer: (item) => void notes.resetTimer(item),
  }

  // Rows can only move within their space — the server refuses anything else.
  const moveTargets = (item: Item) => sections.filter((s) => s.id !== item.sectionId && s.spaceId === section?.spaceId)

  return (
    <div class="app" style={{ '--accent': section ? cssColor(section.color) : undefined }}>
      <Rail sections={sections} activeId={section?.id ?? null} counts={counts} collapsed={collapsed || sections.length === 0}
        onSelect={select} onAdd={() => setOpen({ kind: 'newSection' })}
        onReorder={(id, to) => {
          const rest = sections.filter((s) => s.id !== id)
          void notes.moveSection(id, rest[to - 1] ?? null, rest[to] ?? null)
        }} />

      <main class="main">
        {section ? (
          <>
            <header class="header">
              <button class="rail-toggle" onClick={toggleRail} aria-label={collapsed ? 'Show sections' : 'Hide sections'}>
                <Tile section={section} />
              </button>
              <div class="header-text">
                <h1 class="header-name">{section.name}</h1>
                <div class="header-counts">
                  <span class="dot" />
                  <span>{counts.get(section.id)?.open ?? 0} open</span>
                  {(counts.get(section.id)?.due ?? 0) > 0 && <span class="due-count">· {counts.get(section.id)!.due} due</span>}
                  {status.state !== 'idle' && <span class={`sync-dot ${status.state}`} title={status.state === 'offline' ? 'Offline — changes will sync' : 'Syncing'} />}
                </div>
              </div>
              <button class="icon-btn" aria-label="Section menu" onClick={() => setOpen({ kind: 'sectionMenu' })}><MoreVert size={22} /></button>
            </header>
            <ItemList items={items} now={now} actions={actions} />
            <Composer key={section.id} section={section}
              onSubmit={(text, s) => void notes.addItem(section.id, text, s)} />
          </>
        ) : (
          <div class="empty">
            <h2>No sections yet</h2>
            <p>Create a section to start jotting things down.</p>
            <button class="btn" disabled={!personal} onClick={() => setOpen({ kind: 'newSection' })}>New section</button>
            <button class="link" style={{ marginTop: 24 }} onClick={() => setOpen({ kind: 'account' })}>Account</button>
          </div>
        )}
      </main>

      {open?.kind === 'sectionMenu' && section && (
        <MenuSheet title={section.name} onClose={close} entries={[
          { label: 'Clear…', onSelect: () => setOpen({ kind: 'clear' }) },
          { label: 'Edit section', onSelect: () => setOpen({ kind: 'editSection' }) },
          { label: 'Delete section', danger: true, onSelect: () => setOpen({ kind: 'deleteSection' }) },
          { label: 'Account', onSelect: () => setOpen({ kind: 'account' }) },
        ]} />
      )}
      {open?.kind === 'newSection' && personal && (
        <SectionSheet onClose={close} onSave={async (d) => {
          select((await notes.addSection(personal.id, d.name, d.emoji, d.color)).id)
        }} />
      )}
      {open?.kind === 'editSection' && section && (
        <SectionSheet section={section} onClose={close} onSave={(d) => void notes.updateSection(section.id, d)} />
      )}
      {open?.kind === 'clear' && section && (
        <ClearSheet section={section} checked={totals.get(section.id)?.checked ?? 0} total={totals.get(section.id)?.total ?? 0}
          onClose={close} onClearChecked={() => void notes.clearChecked(section.id)} onClearAll={() => void notes.clearAll(section.id)} />
      )}
      {open?.kind === 'deleteSection' && section && (
        <ConfirmSheet title="Delete section?" confirm="Delete" onClose={close}
          message={`"${section.name}" and all its items will be permanently removed.`}
          onConfirm={() => void notes.deleteSection(section.id)} />
      )}
      {open?.kind === 'itemMenu' && (
        <MenuSheet onClose={close} entries={itemMenu(open.item, moveTargets(open.item), actions, setOpen)} />
      )}
      {open?.kind === 'timer' && (
        <ScheduleSheet initial={open.item} canClear={open.item.dueAt != null || open.item.repeatWeeks != null} onClose={close}
          onSet={(s) => void notes.setSchedule(open.item.id, s.dueAt, s.repeatWeeks)} />
      )}
      {open?.kind === 'move' && (
        <MoveSheet item={open.item} sections={moveTargets(open.item)} onClose={close}
          onMove={(id) => void notes.moveItemToSection(open.item.id, id)} />
      )}
      {open?.kind === 'account' && (
        <Sheet onClose={close} label="Account">
          <h2>Account</h2>
          <p class="sub">{session.email}</p>
          <p class="sub">{status.state === 'offline' ? 'Offline — changes are saved and will sync.' : status.state === 'syncing' ? 'Syncing…' : 'All changes synced.'}</p>
          <p class="sub">Version {__APP_VERSION__}</p>
          <div class="sheet-actions">
            <button class="btn text danger" onClick={() => { close(); onSignOut() }}>Sign out</button>
            <span class="grow" />
            <button class="btn text" onClick={close}>Close</button>
          </div>
        </Sheet>
      )}

      <Snackbar message={snack} onDone={() => setSnack(null)} />
    </div>
  )
}

/** Same entries and order as Android; deliberately no "Edit text" — tapping the text already does it. */
function itemMenu(item: Item, targets: Section[], actions: ItemActions, setOpen: (o: Open) => void) {
  const scheduled = item.dueAt != null || item.repeatWeeks != null
  return [
    { label: item.isChecked ? 'Mark as open' : 'Mark as done', onSelect: () => actions.toggle(item) },
    { label: scheduled ? 'Edit timer' : 'Add timer', onSelect: () => setOpen({ kind: 'timer', item }) },
    ...(item.linkUrl ? [{ label: 'Open link', onSelect: () => window.open(item.linkUrl!, '_blank', 'noopener') }] : []),
    ...(targets.length > 0 ? [{ label: 'Move to section…', onSelect: () => setOpen({ kind: 'move', item }) }] : []),
    { label: 'Delete', danger: true, onSelect: () => actions.delete(item) },
  ]
}
