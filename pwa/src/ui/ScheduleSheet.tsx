import { useState } from 'preact/hooks'
import { dateInputToLocalStartOfDay, formatDueDate, startOfDayIn, toDateInputValue } from '../data/schedule.ts'
import { Sheet } from './Sheet.tsx'

export interface Schedule { dueAt: number | null; repeatWeeks: number | null }

const PRESETS: [string, number][] = [['Tomorrow', 1], ['1 week', 7], ['2 weeks', 14], ['1 month', 30]]

/**
 * Same choices as Android's ScheduleDialog: quick picks (local midnight N days out), any date,
 * and an optional repeat every 1–52 weeks. A repeat with no date is owed straight away.
 */
export function ScheduleSheet({ initial, canClear, onSet, onClose }: {
  initial: Schedule; canClear: boolean; onSet: (s: Schedule) => void; onClose: () => void
}) {
  const [now] = useState(() => Date.now())
  const [dueAt, setDueAt] = useState<number | null>(initial.dueAt)
  const [repeat, setRepeat] = useState<number | null>(initial.repeatWeeks)
  const presetValue = (days: number) => startOfDayIn(now, days)
  const matchesPreset = dueAt != null && PRESETS.some(([, d]) => presetValue(d) === dueAt)

  function set() {
    // Repeat on but no date: due today, so the cycle starts from the first reset.
    const finalDue = dueAt ?? (repeat != null ? startOfDayIn(now, 0) : null)
    onSet({ dueAt: finalDue, repeatWeeks: repeat })
    onClose()
  }

  return (
    <Sheet onClose={onClose} label="Timer">
      <h2>Timer</h2>
      <div class="label">Due</div>
      <div class="chips">
        {PRESETS.map(([label, days]) => {
          const v = presetValue(days)
          return (
            <button key={label} type="button" class={dueAt === v ? 'chip on' : 'chip'}
              onClick={() => setDueAt(dueAt === v ? null : v)}>{label}</button>
          )
        })}
        <label class={dueAt != null && !matchesPreset ? 'chip on' : 'chip'} style={{ position: 'relative' }}>
          {dueAt != null && !matchesPreset ? formatDueDate(dueAt) : 'Pick a date'}
          <input type="date" value={dueAt != null ? toDateInputValue(dueAt) : ''}
            onChange={(e) => { const v = e.currentTarget.value; setDueAt(v ? dateInputToLocalStartOfDay(v) : null) }}
            // iOS opens its native picker on tap of the (invisible) input; the label routes the tap there.
            style={{ position: 'absolute', inset: 0, opacity: 0, width: '100%', height: '100%', pointerEvents: 'auto' }} />
        </label>
      </div>

      <div class="repeat">
        <span>Repeat</span>
        <button type="button" role="switch" aria-checked={repeat != null} class={repeat != null ? 'switch on' : 'switch'}
          onClick={() => setRepeat(repeat != null ? null : 2)} aria-label="Repeat" />
      </div>
      {repeat != null && (
        <div class="stepper">
          <button type="button" aria-label="Fewer weeks" disabled={repeat <= 1} onClick={() => setRepeat(Math.max(1, repeat - 1))}>−</button>
          <span>{repeat === 1 ? 'every week' : `every ${repeat} weeks`}</span>
          <button type="button" aria-label="More weeks" disabled={repeat >= 52} onClick={() => setRepeat(Math.min(52, repeat + 1))}>+</button>
        </div>
      )}

      <div class="sheet-actions">
        {canClear && <button type="button" class="btn text danger" onClick={() => { onSet({ dueAt: null, repeatWeeks: null }); onClose() }}>Clear</button>}
        <span class="grow" />
        <button type="button" class="btn text" onClick={onClose}>Cancel</button>
        <button type="button" class="btn text" onClick={set}>Set</button>
      </div>
    </Sheet>
  )
}
