// Ported from Android's ScheduleTest, PushedAtFormatTest, GraphemeTest, SectionGlyphTest and
// ItemDisplayTextTest. Runs with TZ=Europe/Berlin (see package.json) so the DST case is real.
import { describe, expect, it } from 'vitest'
import { displayText, firstGrapheme, glyph, hasEmoji, linkDomain, nextIconInput } from './display.ts'
import {
  countsBySection, dateInputToLocalStartOfDay, formatDueRelative, isDue, isScheduled, isWaiting,
  nextDueAt, startOfDayIn, totalsBySection,
} from './schedule.ts'

const NOW = 1789819200000 // 2026-09-19 12:00 UTC
const local = (ms: number) => {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

describe('due state', () => {
  it('runs in Berlin time', () => expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('Europe/Berlin'))
  it('an unscheduled note is neither due nor waiting', () => {
    const plain = { dueAt: null, repeatWeeks: null }
    expect([isDue(plain, NOW), isWaiting(plain, NOW), isScheduled(plain)]).toEqual([false, false, false])
  })
  it('a due date exactly now counts as due, not waiting', () => {
    expect([isDue({ dueAt: NOW }, NOW), isWaiting({ dueAt: NOW }, NOW)]).toEqual([true, false])
  })
  it('a repeat with no due date is scheduled but not waiting', () => {
    const r = { dueAt: null, repeatWeeks: 2 }
    expect([isScheduled(r), isWaiting(r, NOW)]).toEqual([true, false])
  })
})

describe('counts', () => {
  const n = (sectionId: string, isChecked: boolean, dueAt: number | null, count: number) =>
    Array.from({ length: count }, () => ({ sectionId, isChecked, dueAt }))
  it('unscheduled and due both count as open, only dated ones as due', () => {
    expect(countsBySection([...n('1', false, null, 3), ...n('1', false, NOW - 1, 2)], NOW).get('1')).toEqual({ open: 5, due: 2 })
  })
  it('waiting notes count towards neither', () => {
    expect(countsBySection([...n('1', false, null, 1), ...n('1', false, NOW + 1, 4)], NOW).get('1')).toEqual({ open: 1, due: 0 })
  })
  it('a section with only waiting notes is absent rather than zero', () => {
    expect(countsBySection(n('7', false, NOW + 1, 2), NOW).has('7')).toBe(false)
  })
  it('checked notes count towards neither', () => {
    expect(countsBySection([...n('1', false, null, 2), ...n('1', true, null, 5), ...n('1', true, NOW - 1, 3)], NOW).get('1'))
      .toEqual({ open: 2, due: 0 })
  })
  it('clear totals ignore the clock', () => {
    expect(totalsBySection([...n('1', false, null, 2), ...n('1', true, null, 5), ...n('1', false, NOW + 1, 3)]).get('1'))
      .toEqual({ checked: 5, total: 10 })
  })
})

describe('calendar arithmetic', () => {
  it('a repeat crossing the DST boundary keeps its wall-clock time', () => {
    const start = Date.parse('2026-10-20T07:00:00Z') // 09:00 Berlin
    const next = nextDueAt(start, 2)
    expect(local(next)).toBe('2026-11-03T09:00')
    expect(next).not.toBe(start + 2 * 7 * 24 * 60 * 60 * 1000)
  })
  it('a quick pick lands on midnight of the target day', () => {
    expect(local(startOfDayIn(Date.parse('2026-09-19T21:40:00Z'), 14))).toBe('2026-10-03T00:00')
  })
  it('a picked date is read as local midnight, not UTC midnight', () => {
    expect(local(dateInputToLocalStartOfDay('2026-10-03'))).toBe('2026-10-03T00:00')
  })
})

describe('formatDueRelative', () => {
  it('reads in calendar days, not elapsed hours', () => {
    const lateEvening = Date.parse('2026-09-19T21:30:00Z') // 23:30 Berlin
    const nextMorning = Date.parse('2026-09-20T06:00:00Z')
    expect(formatDueRelative(nextMorning, lateEvening)).toBe('tomorrow')
  })
  it('weeks take over past a fortnight, and overdue counts backwards', () => {
    const at = (days: number) => NOW + days * 86_400_000
    expect([0, 12, 21, -1, -3].map((d) => formatDueRelative(at(d), NOW))).toEqual(['today', 'in 12d', 'in 3w', 'yesterday', '3d ago'])
  })
})

describe('glyph', () => {
  const s = (name: string, emoji: string | null = null) => ({ name, emoji })
  it('uses an emoji verbatim', () => { expect(glyph(s('Shopping', '🛒'))).toBe('🛒'); expect(hasEmoji(s('x', '🛒'))).toBe(true) })
  it("falls back to the name's initial, uppercased", () => expect(glyph(s('shopping'))).toBe('S'))
  it('treats a blank emoji as none', () => {
    for (const blank of ['', ' ', '\n']) { expect(hasEmoji(s('Work', blank))).toBe(false); expect(glyph(s('Work', blank))).toBe('W') }
  })
  it('ignores leading whitespace', () => expect(glyph(s('  food'))).toBe('F'))
  it('a nameless section still yields something', () => { expect(glyph(s(''))).toBe('•'); expect(glyph(s('   '))).toBe('•') })
  it('keeps non-ASCII initials', () => { expect(glyph(s('übersicht'))).toBe('Ü'); expect(glyph(s('études'))).toBe('É') })
  it('does not truncate multi-codepoint emoji', () => expect(glyph(s('x', '❤️'))).toBe('❤️'))
})

describe('icon input', () => {
  it('keeps clusters whole', () => {
    expect(firstGrapheme('❤️')).toBe('❤️')
    expect(firstGrapheme('👍🏽')).toBe('👍🏽')
    expect(firstGrapheme('👨‍👩‍👧‍👦 family')).toBe('👨‍👩‍👧‍👦')
    expect(firstGrapheme('🛒 shopping')).toBe('🛒')
    expect(firstGrapheme('  🛒  ')).toBe('🛒')
    expect([firstGrapheme(''), firstGrapheme('   ')]).toEqual([null, null])
  })
  it('a newly tapped emoji replaces the old one', () => {
    expect(nextIconInput('🛒', '🛒🍳')).toBe('🍳')
    expect(nextIconInput('❤️', '❤️👍🏽')).toBe('👍🏽')
  })
  it('typing into an empty field takes the first character', () => {
    expect(nextIconInput(null, '🛒')).toBe('🛒')
    expect(nextIconInput('', '🛒 shopping')).toBe('🛒')
  })
  it('replacing a selection reads from the front', () => {
    expect(nextIconInput('🛒', '🍳')).toBe('🍳')
    expect(nextIconInput('🛒', '🍳🛒')).toBe('🍳')
  })
  it('emptying the field clears the icon', () => expect(nextIconInput('🛒', '  ')).toBeNull())
})

describe('displayText', () => {
  const item = (text: string, linkUrl: string | null = null, linkTitle: string | null = null) => ({ text, linkUrl, linkTitle })
  it('a plain note shows its text', () => expect(displayText(item('Zahnreinigung'))).toBe('Zahnreinigung'))
  it('a fetched title wins', () => {
    const u = 'https://www.eppendorfer-grillstation.de/wochenkarte'
    expect(displayText(item(u, u, 'Wochenkarte - Eppendorfer Grill-Station'))).toBe('Wochenkarte - Eppendorfer Grill-Station')
  })
  it('a tracking URL falls back to a readable slug', () => {
    const u = 'https://www.etsy.com/de/listing/1878539213/tamperstation-aus-beton-fur?ga_order=most_relevant'
    expect(displayText(item(u, u))).toBe('Tamperstation aus beton fur')
  })
  it('a numeric-only path shows the raw URL', () => {
    const u = 'https://de.pinterest.com/pin/959196420635110631/'
    expect(displayText(item(u, u))).toBe(u)
  })
  it('handles a trailing slash and a file extension', () => {
    expect(displayText(item('a', 'https://example.com/some-nice-page/'))).toBe('Some nice page')
    expect(displayText(item('b', 'https://example.com/wochen-karte.html'))).toBe('Wochen karte')
  })
  it('a bare domain shows the URL', () => {
    const u = 'https://www.kaufland.de/'
    expect(displayText(item(u, u))).toBe(u)
  })
  it('strips www and port from the domain', () => {
    expect(linkDomain({ linkUrl: 'https://www.chefkoch.de/rezepte/1' })).toBe('chefkoch.de')
    expect(linkDomain({ linkUrl: 'https://de.pinterest.com/pin/1/' })).toBe('de.pinterest.com')
    expect(linkDomain({ linkUrl: null })).toBeNull()
  })
})
