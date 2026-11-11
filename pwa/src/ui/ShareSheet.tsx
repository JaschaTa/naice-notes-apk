import { useEffect, useState } from 'preact/hooks'
import type { Section } from '../data/model.ts'
import type { Person, Sharing } from '../data/sharing.ts'
import { Sheet } from './Sheet.tsx'

/** Your relation to a section: your own private one, one you shared, or one shared with you. */
export type SectionRole = 'private' | 'owner' | 'member'
export type ShareStep = 'main' | 'unshare' | 'leave'

/**
 * Share a private section; for a shared one, who's in it plus "Stop sharing" (owner) or
 * "Leave section" (member). These are server calls, so unlike everything else they need a
 * connection — errors are shown in the sheet rather than queued.
 */
export function ShareSheet({ section, role, myEmail, sharing, initialStep = 'main', onDone, onClose }: {
  section: Section; role: SectionRole; myEmail: string; sharing: Sharing; initialStep?: ShareStep
  onDone: (message: string) => void; onClose: () => void
}) {
  const [step, setStep] = useState<ShareStep>(initialStep)
  const [people, setPeople] = useState<Person[] | null>(null)
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadPeople = () => {
    if (role === 'private') return
    sharing.people().then(
      (all) => setPeople(all.filter((p) => p.spaceId === section.spaceId)),
      () => setPeople(null),
    )
  }
  useEffect(loadPeople, [section.spaceId, role])

  const me = myEmail.toLowerCase()
  const owner = people?.find((p) => p.role === 'owner')
  const others = (people ?? []).filter((p) => p.email !== me)
  const ownerName = owner?.email ?? 'The owner'
  const host = location.host

  async function run(action: () => Promise<void>, message: string, close = true) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onDone(message)
      if (close) onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const share = (e: Event) => {
    e.preventDefault()
    const to = email.trim().toLowerCase()
    if (!to) return
    void run(async () => {
      await sharing.share(section, to)
      setEmail('')
      loadPeople()
    }, `Shared "${section.name}" with ${to}`, role === 'private')
  }

  const emailField = (
    <input class="field" type="email" inputMode="email" autoComplete="off" autoCapitalize="none" spellcheck={false}
      placeholder="name@example.com" value={email} disabled={busy} autoFocus={role === 'private'}
      onInput={(e) => setEmail(e.currentTarget.value)} />
  )
  const errorLine = error && <p class="error" role="alert">{error}</p>

  if (step === 'unshare') {
    const names = others.map((p) => p.email).join(', ') || 'everyone else'
    return (
      <Sheet onClose={onClose} label="Stop sharing">
        <h2>Stop sharing?</h2>
        <p class="sub">"{section.name}" stays with you, notes and all, and disappears for {names}.</p>
        {errorLine}
        <div class="sheet-actions">
          <button class="btn text" disabled={busy} onClick={() => (initialStep === 'main' ? setStep('main') : onClose())}>Cancel</button>
          <button class="btn text danger" disabled={busy}
            onClick={() => void run(() => sharing.unshare(section), `"${section.name}" is private again`)}>
            {busy ? 'Stopping…' : 'Stop sharing'}
          </button>
        </div>
      </Sheet>
    )
  }

  if (step === 'leave') {
    return (
      <Sheet onClose={onClose} label="Leave section">
        <h2>Leave section?</h2>
        <p class="sub">"{section.name}" disappears from your sections. {ownerName} keeps it and can share it with you again.</p>
        {errorLine}
        <div class="sheet-actions">
          <button class="btn text" disabled={busy} onClick={() => (initialStep === 'main' ? setStep('main') : onClose())}>Cancel</button>
          <button class="btn text danger" disabled={busy}
            onClick={() => void run(() => sharing.leave(section), `Left "${section.name}"`)}>
            {busy ? 'Leaving…' : 'Leave'}
          </button>
        </div>
      </Sheet>
    )
  }

  if (role === 'private') {
    return (
      <Sheet onClose={onClose} label={`Share "${section.name}"`}>
        <h2>Share "{section.name}"</h2>
        <p class="sub">They'll see it among their own sections and can add, edit and tick off notes. Only you can delete it.</p>
        <form onSubmit={share}>
          <div class="label">Their email</div>
          {emailField}
          <p class="hint">New to Naice Notes? They open {host} and sign in with this email.</p>
          {errorLine}
          <div class="sheet-actions">
            <button type="button" class="btn text" onClick={onClose}>Cancel</button>
            <button class="btn text" disabled={busy || !email.trim()}>{busy ? 'Sharing…' : 'Share'}</button>
          </div>
        </form>
      </Sheet>
    )
  }

  return (
    <Sheet onClose={onClose} label={`Sharing "${section.name}"`}>
      <h2>"{section.name}"</h2>
      <p class="sub">
        {role === 'owner'
          ? 'Shared by you. Everyone here can add, edit and tick off notes; only you can delete it.'
          : `Shared with you by ${ownerName}. You can add, edit and tick off notes; only they can delete it.`}
      </p>

      <div class="label">People</div>
      {people === null && <p class="hint">Can't load who's in it right now.</p>}
      {(people ?? []).map((p) => (
        <div key={p.email} class="person">
          <span class="person-email">{p.email === me ? `${p.email} (you)` : p.email}</span>
          <span class="person-status">{p.role === 'owner' ? 'Owner' : p.joined ? 'Joined' : 'Invited'}</span>
        </div>
      ))}
      {others.some((p) => !p.joined) && <p class="hint">Invited people open {host} and sign in with that email.</p>}

      {role === 'owner' && (
        <form onSubmit={share}>
          <div class="label">Add someone</div>
          <div class="inline-field">
            {emailField}
            <button class="btn" disabled={busy || !email.trim()}>{busy ? '…' : 'Add'}</button>
          </div>
        </form>
      )}
      {errorLine}

      <div class="sheet-actions">
        {role === 'owner'
          ? <button class="btn text danger" disabled={busy} onClick={() => setStep('unshare')}>Stop sharing</button>
          : <button class="btn text danger" disabled={busy} onClick={() => setStep('leave')}>Leave section</button>}
        <span class="grow" />
        <button class="btn text" onClick={onClose}>Close</button>
      </div>
    </Sheet>
  )
}
