import type { ComponentChildren } from 'preact'
import { useEffect } from 'preact/hooks'

/**
 * A bottom sheet — the iOS-native stand-in for Android's dialogs and dropdown menus, and within
 * thumb reach. Tapping the scrim closes it.
 */
export function Sheet({ onClose, children, label }: { onClose: () => void; children: ComponentChildren; label?: string }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div class="scrim" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet" role="dialog" aria-modal="true" aria-label={label}>
        <div class="sheet-grip" />
        {children}
      </div>
    </div>
  )
}

export interface MenuEntry { label: string; onSelect: () => void; danger?: boolean }

export function MenuSheet({ title, entries, onClose }: { title?: string; entries: MenuEntry[]; onClose: () => void }) {
  return (
    <Sheet onClose={onClose} label={title}>
      {title && <p class="sub">{title}</p>}
      {entries.map((e) => (
        <button key={e.label} class={e.danger ? 'menu-item danger' : 'menu-item'} onClick={() => { onClose(); e.onSelect() }}>
          {e.label}
        </button>
      ))}
    </Sheet>
  )
}

export function ConfirmSheet({ title, message, confirm, onConfirm, onClose }: {
  title: string; message: string; confirm: string; onConfirm: () => void; onClose: () => void
}) {
  return (
    <Sheet onClose={onClose} label={title}>
      <h2>{title}</h2>
      <p class="sub">{message}</p>
      <div class="sheet-actions">
        <button class="btn text" onClick={onClose}>Cancel</button>
        <button class="btn text danger" onClick={() => { onClose(); onConfirm() }}>{confirm}</button>
      </div>
    </Sheet>
  )
}
