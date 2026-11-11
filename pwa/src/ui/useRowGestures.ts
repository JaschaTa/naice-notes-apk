// Long-press to drag, horizontal swipe to delete — for a vertical list of rows.
//
// Built on touch events rather than pointer events on purpose: on iOS a vertical pointer move
// inside a scroller gets claimed by the browser (pointercancel) unless every touchmove is
// cancelled, and only touch events let us cancel them. Mouse events cover desktop.
//
// Rules, matching Android:
// - Long press (no movement) picks a row up; moving then reorders. Rows marked not draggable
//   (waiting notes) are neither picked up nor used as drop targets.
// - A horizontal move first claims the gesture as a swipe; the row deletes only if the finger
//   travelled at least SWIPE_DELETE_FRACTION of the row's width — a fast short flick doesn't.
// - Any other early movement is a scroll, and the gesture is abandoned.
import { useEffect, useRef, useState } from 'preact/hooks'

export const SWIPE_DELETE_FRACTION = 0.45
const LONG_PRESS_MS = 380
const SLOP = 10

type Mode = 'pending' | 'drag' | 'swipe'

interface Gesture {
  id: string
  mode: Mode
  startX: number
  startY: number
  el: HTMLElement
  timer?: ReturnType<typeof setTimeout>
  // drag
  order?: string[]
  from?: number
  mids?: number[]
  height?: number
}

export interface DragView { id: string; dy: number; to: number; from: number; height: number; order: string[] }
export interface SwipeView { id: string; dx: number }

export function useRowGestures(opts: {
  /** Draggable ids in rendered order. */
  ids: string[]
  onReorder?: (id: string, toIndex: number, order: string[]) => void
  onSwipe?: (id: string) => void
  /** Rows the gesture may start on; defaults to ids. Swipe works on any row in this set. */
  swipeIds?: string[]
}) {
  const g = useRef<Gesture | null>(null)
  const suppressClick = useRef(false)
  // State drives rendering; the refs hold the same values for the event handlers, which must not
  // trigger side effects from inside a state updater (those can run more than once).
  const [drag, setDragState] = useState<DragView | null>(null)
  const [swipe, setSwipeState] = useState<SwipeView | null>(null)
  const dragRef = useRef<DragView | null>(null)
  const swipeRef = useRef<SwipeView | null>(null)
  const setDrag = (d: DragView | null) => { dragRef.current = d; setDragState(d) }
  const setSwipe = (s: SwipeView | null) => { swipeRef.current = s; setSwipeState(s) }
  const optsRef = useRef(opts)
  optsRef.current = opts

  // While a drag or swipe owns the gesture, stop the page from scrolling under it.
  useEffect(() => {
    const block = (e: TouchEvent) => { if (g.current && g.current.mode !== 'pending') e.preventDefault() }
    document.addEventListener('touchmove', block, { passive: false })
    return () => document.removeEventListener('touchmove', block)
  }, [])

  function start(id: string, x: number, y: number, el: HTMLElement) {
    cancel()
    const gesture: Gesture = { id, mode: 'pending', startX: x, startY: y, el }
    if (optsRef.current.onReorder && optsRef.current.ids.includes(id)) {
      gesture.timer = setTimeout(() => beginDrag(gesture), LONG_PRESS_MS)
    }
    g.current = gesture
  }

  function beginDrag(gesture: Gesture) {
    if (g.current !== gesture || gesture.mode !== 'pending') return
    const order = [...optsRef.current.ids]
    const rows = order.map((id) => document.querySelector<HTMLElement>(`[data-row-id="${CSS.escape(id)}"]`))
    if (rows.some((r) => !r)) return
    const rects = rows.map((r) => r!.getBoundingClientRect())
    gesture.mode = 'drag'
    gesture.order = order
    gesture.from = order.indexOf(gesture.id)
    gesture.mids = rects.map((r) => r.top + r.height / 2)
    gesture.height = rects[gesture.from].height
    suppressClick.current = true
    navigator.vibrate?.(10)
    setDrag({ id: gesture.id, dy: 0, from: gesture.from, to: gesture.from, height: gesture.height, order })
  }

  function move(x: number, y: number) {
    const gesture = g.current
    if (!gesture) return
    const dx = x - gesture.startX
    const dy = y - gesture.startY
    if (gesture.mode === 'pending') {
      if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return
      clearTimeout(gesture.timer)
      const canSwipe = optsRef.current.onSwipe && (optsRef.current.swipeIds ?? optsRef.current.ids).includes(gesture.id)
      if (canSwipe && Math.abs(dx) > Math.abs(dy)) {
        gesture.mode = 'swipe'
        suppressClick.current = true
      } else {
        g.current = null // a scroll
        return
      }
    }
    if (gesture.mode === 'swipe') setSwipe({ id: gesture.id, dx })
    if (gesture.mode === 'drag') {
      const centre = gesture.mids![gesture.from!] + dy
      // Target = how many other rows' midpoints the dragged row's centre has passed.
      let to = 0
      gesture.mids!.forEach((mid, i) => { if (i !== gesture.from && centre > mid) to++ })
      if (dragRef.current) setDrag({ ...dragRef.current, dy, to })
    }
  }

  function end() {
    const gesture = g.current
    g.current = null
    if (!gesture) return
    clearTimeout(gesture.timer)
    if (gesture.mode === 'swipe') {
      const s = swipeRef.current
      const width = gesture.el.getBoundingClientRect().width
      setSwipe(null)
      if (s && Math.abs(s.dx) >= width * SWIPE_DELETE_FRACTION) optsRef.current.onSwipe?.(s.id)
    }
    if (gesture.mode === 'drag') {
      const d = dragRef.current
      setDrag(null)
      if (d && d.to !== d.from) optsRef.current.onReorder?.(d.id, d.to, d.order)
    }
    // The click that follows touchend belongs to the gesture, not to whatever is under the finger.
    setTimeout(() => { suppressClick.current = false }, 0)
  }

  function cancel() {
    if (g.current) clearTimeout(g.current.timer)
    g.current = null
    setDrag(null)
    setSwipe(null)
  }

  /** Props for a row element. */
  function bind(id: string) {
    return {
      'data-row-id': id,
      onTouchStart: (e: TouchEvent) => {
        if (e.touches.length !== 1 || isInteractive(e.target)) return
        start(id, e.touches[0].clientX, e.touches[0].clientY, e.currentTarget as HTMLElement)
      },
      onTouchMove: (e: TouchEvent) => move(e.touches[0].clientX, e.touches[0].clientY),
      onTouchEnd: end,
      onTouchCancel: cancel,
      onMouseDown: (e: MouseEvent) => {
        if (e.button !== 0 || isInteractive(e.target)) return
        start(id, e.clientX, e.clientY, e.currentTarget as HTMLElement)
        const mm = (ev: MouseEvent) => move(ev.clientX, ev.clientY)
        const mu = () => { end(); window.removeEventListener('mousemove', mm); window.removeEventListener('mouseup', mu) }
        window.addEventListener('mousemove', mm)
        window.addEventListener('mouseup', mu)
      },
      onClickCapture: (e: MouseEvent) => { if (suppressClick.current) { e.stopPropagation(); e.preventDefault() } },
      onContextMenu: (e: Event) => e.preventDefault(),
    }
  }

  /** translateY for a row while another (or it) is being dragged. */
  function offsetFor(id: string): number {
    if (!drag) return 0
    if (id === drag.id) return drag.dy
    const i = drag.order.indexOf(id)
    if (i < 0) return 0
    if (drag.from < drag.to && i > drag.from && i <= drag.to) return -drag.height
    if (drag.from > drag.to && i >= drag.to && i < drag.from) return drag.height
    return 0
  }

  return { bind, drag, swipe, offsetFor }
}

/** Inputs and the inline editor keep their own touch behaviour. */
function isInteractive(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && !!target.closest('input, textarea, [contenteditable], .tooltip')
}

/** Neighbours of the dropped row in the new order, for a fractional key between them. */
export function neighboursAfterMove<T extends { id: string }>(list: T[], id: string, to: number): [T | null, T | null] {
  const rest = list.filter((x) => x.id !== id)
  return [rest[to - 1] ?? null, rest[to] ?? null]
}
