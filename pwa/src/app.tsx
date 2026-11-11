import { useEffect, useState } from 'preact/hooks'
import { isInstalled, isIos } from './platform.ts'

type Backend = 'checking' | 'reachable' | 'unreachable' | 'not configured'

async function checkBackend(): Promise<Backend> {
  const url = import.meta.env.VITE_SUPABASE_URL
  const key = import.meta.env.VITE_SUPABASE_ANON_KEY
  if (!url || !key) return 'not configured'
  try {
    const res = await fetch(`${url}/auth/v1/health`, { headers: { apikey: key } })
    return res.ok ? 'reachable' : 'unreachable'
  } catch {
    return 'unreachable'
  }
}

export function App() {
  const [backend, setBackend] = useState<Backend>('checking')
  useEffect(() => { checkBackend().then(setBackend) }, [])

  const showInstallHint = isIos() && !isInstalled()

  return (
    <main>
      <img class="logo" src="/icon-192.png" alt="" width="96" height="96" />
      <h1>Naice Notes</h1>
      <p class="lead">Shared lists are on their way.</p>

      {showInstallHint && (
        <section class="card">
          <h2>Add to your home screen</h2>
          <ol>
            <li>Tap the <strong>Share</strong> button in Safari</li>
            <li>Choose <strong>Add to Home Screen</strong></li>
            <li>Open Naice Notes from its new icon</li>
          </ol>
        </section>
      )}

      <footer>
        <span>v{__APP_VERSION__}</span>
        <span class={`status ${backend.replace(' ', '-')}`}>backend: {backend}</span>
      </footer>
    </main>
  )
}
