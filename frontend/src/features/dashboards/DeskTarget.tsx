import { useState } from 'react'
import { Goal, Pencil } from 'lucide-react'
import { setMemberTarget } from './pmMetricsApi'
import { targetInput } from './targetProgress'

/**
 * The GM's control for one member's monthly target (D75), on that member's own dashboard: "Set target" when
 * there is none, a pen when there is one, and an inline field either way. A Sales member is held to won value
 * and a Marketing member to new leads, so the unit follows the role and the GM only ever types a number.
 */
export function DeskTarget({
  memberId,
  name,
  role,
  target,
  month,
  onSaved,
}: {
  memberId: string
  name: string
  role: 'SALES' | 'MARKETING'
  /** Null when nobody has set one. */
  target: number | null
  /** The target's month, `YYYY-MM-01`. */
  month: string
  onSaved(): void
}) {
  const input = targetInput(role, target)
  const [editing, setEditing] = useState(false)
  const [amount, setAmount] = useState('')
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)

  async function save() {
    setSaving(true)
    setRefusal(null)
    try {
      await setMemberTarget(memberId, month, Number(amount))
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
          step={input.step}
          required
          autoFocus
          aria-label={`Monthly target for ${name}`}
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

  const edit = () => {
    setAmount(input.initial)
    setEditing(true)
  }

  return target === null ? (
    <button
      type="button"
      onClick={edit}
      className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-xl px-3 py-1.5 text-sm font-medium hover:opacity-80"
      style={{ background: 'var(--accent-soft)', color: 'var(--accent-primary)' }}
    >
      <Goal className="h-4 w-4" aria-hidden />
      Set target
    </button>
  ) : (
    <button
      type="button"
      onClick={edit}
      className="rounded p-1 hover:bg-(--bg-raised)"
      aria-label={`Edit the monthly target for ${name}`}
      title="Edit target"
    >
      <Pencil className="h-3.5 w-3.5" style={{ color: 'var(--text-muted)' }} aria-hidden />
    </button>
  )
}
