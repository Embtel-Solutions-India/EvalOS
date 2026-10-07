import { useState } from 'react'
import { formatMoney } from '../../lib/money'
import { setMemberTarget, type GmDeskRow } from './pmMetricsApi'
import { progressPct } from './targetProgress'

/**
 * One desk's monthly target on the GM's "By desk" row (D75): the target, how far the desk is, and an
 * inline field to change it. A Sales desk is held to won value and a Marketing desk to new leads, so
 * the unit follows the role and the GM only ever types a number.
 */
export function DeskTarget({
  row,
  target,
  month,
  onSaved,
}: {
  row: GmDeskRow
  /** Null when nobody has set one — shown as "Not set", never as 0. */
  target: number | null
  /** The overview's month, `YYYY-MM-01`. */
  month: string
  onSaved(): void
}) {
  const money = row.role === 'SALES'
  const progress = money ? row.wonValue : row.newLeads
  const pct = progressPct(progress, target)
  const show = (n: number) => (money ? formatMoney(Math.round(n)) : String(Math.round(n)))

  const [editing, setEditing] = useState(false)
  const [amount, setAmount] = useState('')
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)

  async function save() {
    setSaving(true)
    setRefusal(null)
    try {
      await setMemberTarget(row.memberId, month, Number(amount))
      setEditing(false)
      onSaved()
    } catch (error: unknown) {
      setRefusal(error instanceof Error ? error.message : 'The target was not saved')
    } finally {
      setSaving(false)
    }
  }

  if (editing) {
    return (
      <form
        className="flex flex-wrap items-center justify-end gap-1"
        onSubmit={(event) => {
          event.preventDefault()
          void save()
        }}
      >
        <input
          type="number"
          min={0}
          step={1}
          required
          autoFocus
          aria-label={`Monthly target for ${row.name}`}
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          className="font-num w-24 rounded-md border px-2 py-0.5 text-sm tabular-nums"
          style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)', color: 'var(--text-primary)' }}
        />
        <button
          type="submit"
          disabled={saving || amount === ''}
          className="rounded-md px-2 py-0.5 text-xs font-medium text-white disabled:opacity-60"
          style={{ background: 'var(--accent-primary)' }}
        >
          Save
        </button>
        <button type="button" onClick={() => setEditing(false)} className="rounded-md border px-2 py-0.5 text-xs" style={{ borderColor: 'var(--border-default)' }}>
          Cancel
        </button>
        {refusal && (
          <span className="w-full text-xs" style={{ color: 'var(--status-red)' }}>
            {refusal}
          </span>
        )}
      </form>
    )
  }

  return (
    <button
      type="button"
      onClick={() => {
        setAmount(target === null ? '' : String(Math.round(target)))
        setEditing(true)
      }}
      className="font-num text-right tabular-nums"
      title="Set this month's target"
    >
      {target === null ? (
        <span style={{ color: 'var(--text-muted)' }}>Not set</span>
      ) : (
        <>
          {show(progress)} / {show(target)}
          {pct !== null && (
            <span className="ml-1 text-xs" style={{ color: 'var(--text-muted)' }}>
              {pct}%
            </span>
          )}
        </>
      )}
    </button>
  )
}
