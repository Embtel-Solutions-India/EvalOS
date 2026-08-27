import { useMemo, useState } from 'react'
import { formatPayout } from '../../lib/money'
import { settle } from './payoutApi'
import { settleBlocker, sumSelected, type ExpertGroup } from './payoutRules'

/**
 * Record one transfer against several drafts.
 *
 * **The amount is read-only, and that is the design rather than a shortcut.** The server
 * refuses any payment whose amount is not exactly the sum of the drafts it settles, so a
 * free-text amount would be a field the server rejects every time it disagrees with the
 * ticks — and a field that can only ever be wrong is worse than no field. Unticking a draft
 * changes the total in front of the user, which is what makes settling-by-selection legible.
 *
 * **Method is a datalist, not a dropdown.** EvalOS has no payment rail and must not pretend to
 * enumerate one; the suggestions are conveniences, and anything typed is accepted, because the
 * real constraint lives at whatever app actually moved the money.
 *
 * EvalOS does not send the transfer. Somebody sends it outside this system and records it here.
 */

const METHOD_SUGGESTIONS = ['Zelle', 'ACH', 'Wire', 'PayPal', 'Cheque']

/** Today in the viewer's own zone, as an ISO day for the date input's default and max. */
function todayIso(): string {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

export default function PaymentForm({
  group,
  onRecorded,
  onCancel,
}: {
  group: ExpertGroup
  onRecorded: () => void
  onCancel: () => void
}) {
  // Everything ticked by default: settling the whole week is the common case, and unticking
  // is the exception the ENM makes deliberately.
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(group.drafts.filter((d) => d.status === 'PENDING' && d.amount !== null).map((d) => d.id)),
  )
  const [method, setMethod] = useState('Zelle')
  const [reference, setReference] = useState('')
  const [paidDate, setPaidDate] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)

  const amount = useMemo(() => sumSelected(group.drafts, selected), [group.drafts, selected])
  const blocker = useMemo(() => settleBlocker(group.drafts, selected), [group.drafts, selected])
  const missingReference = reference.trim() === ''

  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const record = async () => {
    setSaving(true)
    setRefusal(null)
    try {
      await settle({
        expertId: group.expertId,
        payoutIds: [...selected],
        amount,
        method: method.trim(),
        reference: reference.trim(),
        // The server takes an Instant; the input gives a day. Midday UTC rather than midnight
        // so the date cannot slide backwards a day for a viewer behind UTC.
        paidDate: `${paidDate}T12:00:00Z`,
        notes: notes.trim(),
      })
      onRecorded()
    } catch (error: unknown) {
      // `lib/api`'s interceptor lifts the server's own reason onto `message`, so this shows
      // "the payment is 800.00 but the drafts it settles come to 700.00" rather than
      // "Request failed with status code 400".
      setRefusal(error instanceof Error ? error.message : 'The payment was not recorded')
      setSaving(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgb(0 0 0 / 0.45)' }}
      role="dialog"
      aria-modal="true"
      aria-label={`Record a payment to ${group.expertName}`}
    >
      <div
        className="flex max-h-full w-full max-w-lg flex-col gap-4 overflow-y-auto rounded-lg border p-5"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-base)' }}
      >
        <header>
          <h2 className="text-base font-semibold">Record payment</h2>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            {group.expertName} · money already sent outside EvalOS
          </p>
        </header>

        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
            Drafts this transfer covers
          </legend>
          {group.drafts.map((draft) => {
            const settleable = draft.status === 'PENDING' && draft.amount !== null
            return (
              <label
                key={draft.id}
                className="flex items-center justify-between gap-3 rounded px-2 py-1.5 text-sm"
                style={settleable ? undefined : { color: 'var(--text-muted)' }}
              >
                <span className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={selected.has(draft.id)}
                    disabled={!settleable}
                    onChange={() => toggle(draft.id)}
                  />
                  {draft.caseCode}
                </span>
                <span className="font-num tabular-nums">
                  {draft.amount === null ? 'not set' : formatPayout(draft.amount, draft.currency)}
                </span>
              </label>
            )
          })}
        </fieldset>

        <div className="flex items-center justify-between rounded border px-3 py-2" style={{ borderColor: 'var(--border-default)' }}>
          <span className="text-sm font-medium">Amount</span>
          {/* Not an input. The server accepts exactly this number and nothing else. */}
          <span className="font-num text-lg font-semibold tabular-nums">
            {formatPayout(amount, group.currency)}
          </span>
        </div>

        <label className="flex flex-col gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Payment method</span>
          <input
            list="payout-methods"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
          <datalist id="payout-methods">
            {METHOD_SUGGESTIONS.map((suggestion) => (
              <option key={suggestion} value={suggestion} />
            ))}
          </datalist>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Payment reference</span>
          <input
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            placeholder="ZELLE-08262026-001"
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Payment date</span>
          <input
            type="date"
            value={paidDate}
            max={todayIso()}
            onChange={(e) => setPaidDate(e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Notes (optional)</span>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={2}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>

        {(blocker || missingReference || refusal) && (
          <p className="text-sm" style={{ color: refusal ? 'var(--status-red)' : 'var(--status-amber)' }}>
            {refusal ?? blocker ?? 'A payment reference is required.'}
          </p>
        )}

        <footer className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border px-3 py-1.5 text-sm font-medium"
            style={{ borderColor: 'var(--border-default)' }}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void record()}
            disabled={saving || blocker !== null || missingReference}
            className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
            style={{ background: 'var(--accent-primary)' }}
          >
            {saving ? 'Recording…' : 'Save payment'}
          </button>
        </footer>
      </div>
    </div>
  )
}
