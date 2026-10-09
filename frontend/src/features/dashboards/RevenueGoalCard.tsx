import { useState } from 'react'
import { BarChart3, Pencil } from 'lucide-react'
import { Delta, type CardState } from '../../components/ui/card'
import { formatMoney } from '../../lib/money'
import { setGmGoal } from './pmMetricsApi'

const TONE = { good: 'var(--status-green)', warn: 'var(--status-amber)', bad: 'var(--status-red)' } as const

/**
 * The GM's headline: what has been won, how far that is towards the month's target, and what is left.
 *
 * The target and the bar exist only over a calendar month (`goalMonth` is null on any other window, and
 * the server returns no goal there rather than dividing by a denominator that does not apply), so on any
 * other window the card is the figure alone. The pencil edits the target for the month shown.
 */
export default function RevenueGoalCard({
  state,
  won,
  goal,
  pctToGoal,
  goalMonth,
  change,
  onSaved,
  className,
}: {
  state: CardState
  won: number | null
  goal: number | null
  pctToGoal: number | null
  goalMonth: string | null
  /** Whole-percentage change against the previous period; null draws no arrow. */
  change: number | null
  onSaved: () => void
  className?: string
}) {
  const [editing, setEditing] = useState(false)
  const hasGoal = goal !== null && goal > 0 && pctToGoal !== null
  const tone = !hasGoal ? 'bad' : pctToGoal >= 100 ? 'good' : pctToGoal >= 70 ? 'warn' : 'bad'
  const color = TONE[tone]
  const fill = hasGoal ? Math.min(100, Math.max(0, pctToGoal)) : 0

  return (
    <section
      className={`rounded-[1.25rem] p-5 ${className ?? ''}`}
      style={{ background: 'var(--bg-surface)', boxShadow: 'var(--shadow-soft)' }}
    >
      <div className="flex items-center gap-3">
        <span
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl"
          style={{ background: `color-mix(in srgb, ${color} 12%, transparent)`, color }}
        >
          <BarChart3 className="h-5 w-5" aria-hidden />
        </span>
        <h2 className="min-w-0 flex-1 truncate text-base font-medium" style={{ color: 'var(--text-muted)' }}>
          {goalMonth ? 'Monthly revenue' : 'Business won'}
        </h2>
        {state.kind === 'ok' && goalMonth && (
          <button
            type="button"
            aria-label={goal ? 'Change monthly target' : 'Set monthly target'}
            onClick={() => setEditing((open) => !open)}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg"
            style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}
          >
            <Pencil className="h-4 w-4" aria-hidden />
          </button>
        )}
      </div>

      {state.kind === 'loading' && (
        <div className="mt-4 h-24 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}
      {state.kind === 'error' && (
        <p className="mt-4 text-sm" style={{ color: 'var(--status-red)' }} role="alert">
          {state.note}
        </p>
      )}

      {state.kind === 'ok' && won !== null && (
        <>
          <div className="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="font-num text-4xl leading-none font-bold tracking-tight tabular-nums">
              {formatMoney(Math.round(won))}
            </span>
            {change !== null && <Delta value={change} better="up" />}
          </div>

          {editing && goalMonth && (
            <GoalEditor
              month={goalMonth}
              current={goal}
              onClose={() => setEditing(false)}
              onSaved={() => {
                setEditing(false)
                onSaved()
              }}
            />
          )}

          {hasGoal ? (
            <>
              <div className="relative mt-7">
                <span
                  className="font-num absolute -top-6 text-sm font-semibold tabular-nums"
                  style={{ left: `min(max(${fill}%, 1.5rem), calc(100% - 1.5rem))`, transform: 'translateX(-50%)', color }}
                >
                  {pctToGoal}%
                </span>
                <div
                  role="progressbar"
                  aria-label="Progress to the monthly target"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={fill}
                  className="relative h-3 w-full overflow-hidden rounded-full"
                  style={{ background: 'var(--bg-raised)' }}
                >
                  <div
                    className="h-full rounded-full"
                    style={{
                      width: `${fill}%`,
                      background: `linear-gradient(90deg, color-mix(in srgb, ${color} 70%, #000), ${color})`,
                    }}
                  />
                </div>
              </div>

              <dl className="mt-5 grid grid-cols-2 gap-4">
                <div>
                  <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
                    Monthly target
                  </dt>
                  <dd className="font-num mt-0.5 text-2xl font-bold tabular-nums">{formatMoney(Math.round(goal))}</dd>
                </div>
                <div className="border-l pl-4" style={{ borderColor: 'var(--border-default)' }}>
                  <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
                    Remaining amount
                  </dt>
                  <dd className="font-num mt-0.5 text-2xl font-bold tabular-nums">
                    {formatMoney(Math.max(0, Math.round(goal - won)))}
                  </dd>
                </div>
              </dl>
            </>
          ) : (
            goalMonth && (
              <p className="mt-3 text-sm" style={{ color: 'var(--text-muted)' }}>
                No monthly target set
              </p>
            )
          )}
        </>
      )}
    </section>
  )
}

/** The amount field for the month shown, opened by the pencil. */
function GoalEditor({
  month,
  current,
  onClose,
  onSaved,
}: {
  month: string
  current: number | null
  onClose: () => void
  onSaved: () => void
}) {
  const [amount, setAmount] = useState(current ? String(Math.round(current)) : '')
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)
  const label = new Date(`${month}T12:00:00Z`).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })

  async function save() {
    setSaving(true)
    setRefusal(null)
    try {
      await setGmGoal(month, Number(amount))
      onSaved()
    } catch (error: unknown) {
      setRefusal(error instanceof Error ? error.message : 'The target was not saved')
    } finally {
      setSaving(false)
    }
  }

  return (
    <form
      className="mt-3 flex flex-wrap items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        void save()
      }}
    >
      <label className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Target for {label}{' '}
        <input
          type="number"
          min={0}
          step={1}
          required
          autoFocus
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          className="font-num ml-1 w-32 rounded-md border px-2 py-1 text-sm tabular-nums"
          style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)', color: 'var(--text-primary)' }}
        />
      </label>
      <button
        type="submit"
        disabled={saving || amount === ''}
        className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
        style={{ background: 'var(--accent-primary)' }}
      >
        Save
      </button>
      <button
        type="button"
        onClick={onClose}
        className="rounded-md border px-3 py-1.5 text-sm font-medium"
        style={{ borderColor: 'var(--border-default)' }}
      >
        Cancel
      </button>
      {refusal && (
        <p className="w-full text-sm" style={{ color: 'var(--status-red)' }}>
          {refusal}
        </p>
      )}
    </form>
  )
}
