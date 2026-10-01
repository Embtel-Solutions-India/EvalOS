import { useQuery } from '@tanstack/react-query'
import { useCallback, useState } from 'react'

import { useMe } from '../../lib/authContext'
import { formatPayout } from '../../lib/money'
import OfferLogPanel from './OfferLogPanel'
import { editOfferFee, fetchCaseOffer } from './registerApi'
import { mayEditFee, type OfferOutcome } from './registerRules'

const OUTCOME_WORD: Record<OfferOutcome, string> = {
  OFFERED: 'offered, awaiting the expert',
  ACCEPTED: 'accepted — final',
  DECLINED: 'declined',
  TIMED_OUT: 'timed out',
  SUPERSEDED: 'superseded',
}

/**
 * The case's fee, on the case page's expert card (Unit 65): what the current offer pays, whether
 * the expert has agreed to it, **Edit** while nobody has answered (GM / PM / PC / ENM), and its log.
 *
 * Loads on its own so the card never waits on it: a failure is one muted line, not a broken card.
 */
export default function OfferFee({ caseId }: { caseId: string }) {
  const me = useMe()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [showLog, setShowLog] = useState(false)

  // Case-shaped key (Unit 70a phase 2): a reassign, an acceptance or a live `case.changed` re-reads it.
  const query = useQuery({
    queryKey: ['case', caseId, 'offer'],
    queryFn: ({ signal }) => fetchCaseOffer(caseId, signal),
  })
  const offer = query.data
  const failure = query.isError && offer === undefined ? query.error.message || 'Could not load the fee' : null

  async function save() {
    setSaving(true)
    setRefusal(null)
    try {
      // The PATCH's interceptor re-reads the offer (Unit 70a).
      await editOfferFee(caseId, Number(draft))
      setEditing(false)
    } catch (error: unknown) {
      // Most often a 409: the expert answered while this was open. Reload so the card says so.
      setRefusal(error instanceof Error ? error.message : 'The fee was not saved')
      void query.refetch()
    } finally {
      setSaving(false)
    }
  }

  const closeLog = useCallback(() => setShowLog(false), [])

  if (failure) return <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>{failure}</p>
  if (offer === undefined) return <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>Loading the fee…</p>
  if (offer === null) return null

  const editable = mayEditFee(me.role, offer.outcome)

  return (
    <div className="mt-3 rounded-md border p-3" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <h3 className="text-xs font-semibold tracking-tight">Fee for this case</h3>
      {editing ? (
        <form
          className="mt-1 flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <input
            type="number"
            min="0"
            step="0.01"
            required
            autoFocus
            aria-label="New fee"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="font-num w-28 rounded border px-2 py-1 text-right text-sm tabular-nums"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-base)' }}
          />
          <button type="submit" className="btn btn-primary" disabled={saving || draft === ''}>
            {saving ? 'Saving…' : 'Save fee'}
          </button>
          <button type="button" className="btn" onClick={() => setEditing(false)}>Cancel</button>
        </form>
      ) : (
        <p className="font-num mt-1 text-lg font-semibold tabular-nums">
          {offer.fee !== null && offer.currency ? formatPayout(offer.fee, offer.currency) : 'Not set'}
        </p>
      )}
      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {OUTCOME_WORD[offer.outcome]}
        {offer.feeSetAt &&
          `, set by ${offer.feeSetByName ?? 'the standard fee'} on ${new Date(offer.feeSetAt).toLocaleDateString()}`}
      </p>
      {refusal && <p className="mt-1 text-xs" style={{ color: 'var(--status-red)' }}>{refusal}</p>}
      <div className="mt-2 flex gap-3 text-xs">
        {editable && !editing && (
          <button
            type="button"
            className="underline"
            onClick={() => {
              setDraft(offer.fee === null ? '' : String(offer.fee))
              setRefusal(null)
              setEditing(true)
            }}
          >
            Edit
          </button>
        )}
        <button type="button" className="underline" onClick={() => setShowLog(true)}>History</button>
      </div>
      {showLog && <OfferLogPanel title="Fee history" entries={offer.log} onClose={closeLog} />}
    </div>
  )
}
