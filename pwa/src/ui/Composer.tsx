import { useState } from 'preact/hooks'
import { hasEmoji } from '../data/display.ts'
import type { Section } from '../data/model.ts'
import { DateRange, Send } from './icons.tsx'
import { ScheduleSheet, type Schedule } from './ScheduleSheet.tsx'

/**
 * Single-line input with the timer button and send. The timer arms green and clears on submit —
 * unlike Android's sticky Claude checkbox, which the web app doesn't have.
 */
export function Composer({ section, onSubmit }: { section: Section; onSubmit: (text: string, schedule: Schedule) => void }) {
  const [text, setText] = useState('')
  const [schedule, setSchedule] = useState<Schedule>({ dueAt: null, repeatWeeks: null })
  const [timerOpen, setTimerOpen] = useState(false)
  const armed = schedule.dueAt != null || schedule.repeatWeeks != null
  const placeholder = armed ? `Add to ${section.name}, scheduled`
    : hasEmoji(section) ? `Add to ${section.emoji} ${section.name}` : `Add to ${section.name}`

  function submit(e?: Event) {
    e?.preventDefault()
    const t = text.trim()
    if (!t) return
    onSubmit(t, schedule)
    setText('')
    setSchedule({ dueAt: null, repeatWeeks: null })
  }

  return (
    <form class={text ? 'composer has-text' : 'composer'} onSubmit={submit}>
      <input class="composer-input" value={text} placeholder={placeholder} enterKeyHint="send" autocomplete="off"
        onInput={(e) => setText(e.currentTarget.value)} />
      <div class="composer-tools">
        <button type="button" class={armed ? 'tool armed' : 'tool'} aria-label="Timer" aria-pressed={armed}
          onClick={() => setTimerOpen(true)}><DateRange /></button>
        <span class="spacer" />
        <button class="send" type="submit" disabled={!text.trim()} aria-label="Add"
          // Keep the keyboard up: a mousedown on the button would otherwise blur the input first.
          onMouseDown={(e) => e.preventDefault()}><Send /></button>
      </div>
      {timerOpen && <ScheduleSheet initial={schedule} canClear={armed} onClose={() => setTimerOpen(false)} onSet={setSchedule} />}
    </form>
  )
}
