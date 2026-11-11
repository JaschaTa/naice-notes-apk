import { glyph, hasEmoji } from '../data/display.ts'
import type { Section } from '../data/model.ts'
import { cssColor } from '../data/notes.ts'
import { People } from './icons.tsx'

/**
 * A section's tile. An emoji is its own multicoloured glyph, so it sits on a ~22% wash of the
 * section colour; a letter has no colour of its own and takes white on solid. Same pairing as
 * RailTile/RailToggle/move picker/widget on Android.
 */
export function Tile({ section, active, shared, class: extra = '' }: {
  section: Pick<Section, 'name' | 'emoji' | 'color'>; active?: boolean; shared?: boolean; class?: string
}) {
  const color = cssColor(section.color)
  const emoji = hasEmoji(section)
  return (
    <span class={`tile ${emoji ? '' : 'letter'} ${active ? 'active' : ''} ${extra}`}
      style={{ background: emoji ? `color-mix(in srgb, ${color} 22%, transparent)` : color }}>
      {glyph(section)}
      {shared && <span class="tile-shared"><People /></span>}
    </span>
  )
}
