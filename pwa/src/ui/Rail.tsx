import type { Section } from '../data/model.ts'
import type { SectionCounts } from '../data/schedule.ts'
import { Tile } from './Tile.tsx'
import { useRowGestures } from './useRowGestures.ts'

/** Fixed near-black in both themes: it has to make nine saturated section colours read as accents. */
export function Rail({ sections, sharedIds, activeId, counts, collapsed, onSelect, onAdd, onReorder }: {
  sections: Section[]
  /** Sections someone else can see too; their tiles carry a marker. */
  sharedIds: Set<string>
  activeId: string | null
  counts: Map<string, SectionCounts>
  collapsed: boolean
  onSelect: (id: string) => void
  onAdd: () => void
  onReorder: (id: string, to: number) => void
}) {
  const { bind, drag, offsetFor } = useRowGestures({
    ids: sections.map((s) => s.id),
    onReorder: (id, to) => onReorder(id, to),
  })

  return (
    <nav class={collapsed ? 'rail collapsed' : 'rail'} aria-label="Sections" aria-hidden={collapsed}>
      {sections.map((s) => {
        const active = s.id === activeId
        const c = counts.get(s.id)
        const dy = offsetFor(s.id)
        return (
          <button key={s.id} {...bind(s.id)} class={drag?.id === s.id ? 'rail-entry dragging' : 'rail-entry'}
            style={{ transform: dy ? `translateY(${dy}px)` : undefined, transition: drag && drag.id !== s.id ? 'transform .15s' : undefined }}
            onClick={() => onSelect(s.id)} aria-current={active ? 'page' : undefined} aria-label={sharedIds.has(s.id) ? `${s.name} (shared)` : s.name}>
            {active && <span class="rail-indicator" />}
            <Tile section={s} active={active} shared={sharedIds.has(s.id)} />
            {c && c.open > 0 && (
              <span class={c.due > 0 ? 'badge due' : 'badge'} style={{ top: 0, right: 10 }}>
                <span>{c.open > 99 ? '99+' : c.open}</span>
              </span>
            )}
            <span class={active ? 'rail-name active' : 'rail-name'}>{s.name}</span>
          </button>
        )
      })}
      <div class="rail-add">
        <button class="tile" onClick={onAdd} aria-label="New section">+</button>
      </div>
    </nav>
  )
}
