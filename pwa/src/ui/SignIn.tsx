import type { SupabaseClient } from '@supabase/supabase-js'
import { useState } from 'preact/hooks'

/**
 * Email → 6-digit code. Never a link: on iOS a link opens Safari, not the installed app, and
 * the session would land in the wrong place.
 */
export function SignIn({ sb }: { sb: SupabaseClient }) {
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [step, setStep] = useState<'email' | 'code'>('email')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function sendCode(e: Event) {
    e.preventDefault()
    setBusy(true); setError(null)
    const { error } = await sb.auth.signInWithOtp({ email: email.trim(), options: { shouldCreateUser: true } })
    setBusy(false)
    if (error) setError(error.message.includes('invite-only') ? 'This app is invite-only. Ask for an invite first.' : error.message)
    else setStep('code')
  }

  async function verify(e: Event) {
    e.preventDefault()
    setBusy(true); setError(null)
    const { error } = await sb.auth.verifyOtp({ email: email.trim(), token: code.trim(), type: 'email' })
    setBusy(false)
    if (error) setError(error.message.includes('expired') || error.message.includes('invalid') ? 'That code didn’t work. Check it, or request a new one.' : error.message)
  }

  return (
    <section class="card signin">
      {step === 'email' ? (
        <form onSubmit={sendCode}>
          <h2>Sign in</h2>
          <label>Email
            <input class="field" type="email" autocomplete="email" required value={email}
              onInput={(e) => setEmail(e.currentTarget.value)} />
          </label>
          <button disabled={busy || !email.includes('@')}>{busy ? 'Sending…' : 'Send code'}</button>
        </form>
      ) : (
        <form onSubmit={verify}>
          <h2>Check your email</h2>
          <p class="muted">We sent a 6-digit code to {email}.</p>
          <label>Code
            <input class="field" inputMode="numeric" autocomplete="one-time-code" pattern="[0-9]*" maxLength={8} required
              value={code} onInput={(e) => setCode(e.currentTarget.value.replace(/\D/g, ''))} />
          </label>
          <button disabled={busy || code.length < 6}>{busy ? 'Checking…' : 'Sign in'}</button>
          <button type="button" class="link" onClick={() => { setStep('email'); setCode('') }}>Use a different email</button>
        </form>
      )}
      {error && <p class="error">{error}</p>}
    </section>
  )
}
