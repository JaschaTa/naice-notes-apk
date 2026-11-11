import { useEffect } from 'preact/hooks'

export interface SnackbarMessage { id: number; text: string; action?: { label: string; run: () => void } }

/** One message at a time; a new one replaces the old, as Android dismisses the previous first. */
export function Snackbar({ message, onDone }: { message: SnackbarMessage | null; onDone: () => void }) {
  useEffect(() => {
    if (!message) return
    const t = setTimeout(onDone, 4000) // SnackbarDuration.Short
    return () => clearTimeout(t)
  }, [message?.id])
  if (!message) return null
  return (
    <div class="snackbar" role="status">
      <span>{message.text}</span>
      {message.action && <button onClick={() => { message.action!.run(); onDone() }}>{message.action.label}</button>}
    </div>
  )
}
