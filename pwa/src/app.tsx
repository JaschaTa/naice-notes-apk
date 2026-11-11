import type { Session as AuthSession } from '@supabase/supabase-js'
import type { ComponentChildren } from 'preact'
import { useEffect, useState } from 'preact/hooks'
import { startSession, type Session } from './data/session.ts'
import { supabase } from './lib/supabase.ts'
import { isInstalled, isIos } from './platform.ts'
import { Home } from './ui/Home.tsx'
import { SignIn } from './ui/SignIn.tsx'

export function App() {
  const [auth, setAuth] = useState<AuthSession | null | undefined>(undefined)
  const [session, setSession] = useState<Session | null>(null)

  useEffect(() => {
    if (!supabase) return
    void supabase.auth.getSession().then(({ data }) => setAuth(data.session))
    const { data } = supabase.auth.onAuthStateChange((_event, s) => setAuth(s))
    return () => data.subscription.unsubscribe()
  }, [])

  // Dev-only sample data at /?demo (see dev/demo.ts); the DEV check removes it from production builds.
  const demo = import.meta.env.DEV && new URLSearchParams(location.search).has('demo')
  useEffect(() => {
    if (!demo) return
    let s: Session | null = null
    void import('./dev/demo.ts').then((m) => m.startDemoSession()).then((x) => { s = x; setSession(x) })
    return () => s?.close()
  }, [])

  const userId = auth?.user.id
  useEffect(() => {
    if (!supabase || !userId) return
    let current: Session | null = null
    let cancelled = false
    void startSession(supabase, userId, auth!.user.email ?? '').then((s) => {
      if (cancelled) s.close()
      else { current = s; setSession(s) }
    })
    return () => { cancelled = true; current?.close(); setSession(null) }
  }, [userId])

  async function signOut() {
    if (!supabase || !session) return
    const pending = (await session.store.dirty('items')).length + (await session.store.dirty('sections')).length
    if (pending && !confirm(`${pending} change(s) haven't synced yet and will be lost. Sign out anyway?`)) return
    await session.store.wipe()
    await supabase.auth.signOut()
  }

  if (demo) return session ? <Home session={session} onSignOut={() => {}} /> : <Shell><p class="muted">Loading demo…</p></Shell>
  if (!supabase) return <Shell><p class="error">This build has no backend configured.</p></Shell>
  if (auth === undefined) return <Shell><p class="muted">Loading…</p></Shell>
  if (!auth) return <Shell><InstallHint /><SignIn sb={supabase} /></Shell>
  if (!session) return <Shell><p class="muted">Opening your notes…</p></Shell>
  return <Home session={session} onSignOut={signOut} />
}

function Shell({ children }: { children: ComponentChildren }) {
  return (
    <main class="shell">
      <img class="logo" src="/icon-192.png" alt="" width="80" height="80" />
      <h1>Naice Notes</h1>
      {children}
      <footer><span>v{__APP_VERSION__}</span></footer>
    </main>
  )
}

function InstallHint() {
  if (!isIos() || isInstalled()) return null
  return (
    <section class="card">
      <h2>Add to your home screen</h2>
      <ol>
        <li>Tap the <strong>Share</strong> button in Safari</li>
        <li>Choose <strong>Add to Home Screen</strong></li>
        <li>Open Naice Notes from its new icon</li>
      </ol>
    </section>
  )
}
