// Ports of Android's Section.glyph/hasEmoji, Item.displayText/linkDomain and the icon-field
// input rule (ui/util/Grapheme.kt). Each is drawn in several places, so it lives here once.
import type { Item, Section } from './model.ts'

export const hasEmoji = (s: Pick<Section, 'emoji'>) => !!s.emoji && s.emoji.trim() !== ''

/** The emoji if set, otherwise the name's first letter uppercased, otherwise a dot. Never blank. */
export function glyph(s: Pick<Section, 'emoji' | 'name'>): string {
  if (hasEmoji(s)) return s.emoji!
  const first = firstGrapheme(s.name)
  return first ? first.toUpperCase() : '•'
}

const MIN_SLUG_LETTERS = 4
const isLetter = (c: string) => /\p{L}/u.test(c)

/**
 * "…/listing/1878539213/tamperstation-aus-beton-fur?ga_order=…" → "Tamperstation aus beton fur".
 * Null when no path segment carries real words, so the caller falls back to the raw URL.
 */
function slugToTitle(url: string): string | null {
  const afterScheme = url.includes('://') ? url.slice(url.indexOf('://') + 3) : ''
  const slash = afterScheme.indexOf('/')
  const path = (slash >= 0 ? afterScheme.slice(slash + 1) : '').split('?')[0].split('#')[0]
  const segment = path.split('/').reverse().find((seg) => seg.trim() !== '' && [...seg].some(isLetter))
  if (!segment || [...segment].filter(isLetter).length < MIN_SLUG_LETTERS) return null
  const dot = segment.lastIndexOf('.')
  const base = (dot >= 0 ? segment.slice(0, dot) : segment).replaceAll('-', ' ').replaceAll('_', ' ').trim()
  return base ? base[0].toUpperCase() + base.slice(1) : null
}

/** Fetched page title, else a readable slug, else the note's own text. */
export function displayText(i: Pick<Item, 'linkTitle' | 'linkUrl' | 'text'>): string {
  if (i.linkTitle && i.linkTitle.trim() !== '') return i.linkTitle
  if (i.linkUrl) return slugToTitle(i.linkUrl) ?? i.text
  return i.text
}

/** Bare host for a link card's second line, e.g. "chefkoch.de". */
export function linkDomain(i: Pick<Item, 'linkUrl'>): string | null {
  if (!i.linkUrl || !i.linkUrl.includes('://')) return null
  const host = i.linkUrl.slice(i.linkUrl.indexOf('://') + 3).split('/')[0].split(':')[0].replace(/^www\./, '')
  return host || null
}

const segmenter = typeof Intl !== 'undefined' && 'Segmenter' in Intl
  ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null

/** The first user-perceived character, so ❤️ and 👍🏽 stay whole. Null if there's nothing. */
export function firstGrapheme(input: string): string | null {
  const trimmed = input.trim()
  if (!trimmed) return null
  const first = segmenter ? segmenter.segment(trimmed)[Symbol.iterator]().next().value?.segment : Array.from(trimmed)[0]
  return first && first.trim() ? first : null
}

/**
 * What a one-character icon field should hold after its text went from `current` to `raw`. A
 * text field appends, so with 🛒 in it, picking 🍳 delivers "🛒🍳" — the new part wins, or the
 * emoji keyboard would look dead. Anything else is read from the front.
 */
export function nextIconInput(current: string | null, raw: string): string | null {
  const existing = current ?? ''
  const appended = existing !== '' && raw.length > existing.length && raw.startsWith(existing)
  return firstGrapheme(appended ? raw.slice(existing.length) : raw)
}
