import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { formatPayout } from '../../lib/money'
import { confirmPayment, fetchPayment } from './payoutApi'
import type { PaymentDetailView } from './payoutRules'

/**
 * One transfer, and every draft it settled.
 *
 * This is the screen that answers "what did this $1,100 cover" — the question the WhatsApp
 * group this unit replaces could never answer a week later.
 *
 * **The reference is shown here and nowhere the expert can see.** It names a bank transfer and
 * belongs to the brand's records; the expert portal shows status and amount only.
 *
 * Confirming is terminal and cascades to every draft the payment settled: one transfer gets one
 * acknowledgement, so there is no route that confirms a single draft.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; view: PaymentDetailView }
  | { status: 'failed'; message: string }

const MAY_RECORD = ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER']

export default function PaymentDetail() {
  const { paymentId = '' } = useParams()
  const me = useMe()
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [refusal, setRefusal] = useState<string | null>(null)

  const mayRecord = MAY_RECORD.includes(me.role)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState({ status: 'ready', view: await fetchPayment(paymentId, signal) })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load this payment',
        })
      }
    },
    [paymentId],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  const confirm = async () => {
    setRefusal(null)
    try {
      await confirmPayment(paymentId)
      await load()
    } catch (error: unknown) {
      setRefusal(error instanceof Error ? error.message : 'The payment was not confirmed')
    }
  }

  if (state.status === 'loading') {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading…
      </p>
    )
  }

  if (state.status === 'failed') {
    return (
      <p className="text-sm" style={{ color: 'var(--status-red)' }}>
        {state.message}
      </p>
    )
  }

  const { payment, notes, recordedByName, drafts } = state.view

  return (
    <div className="flex flex-col gap-5">
      <header>
        <Link
          to={`/payouts/experts/${payment.expertId}`}
          className="text-xs"
          style={{ color: 'var(--text-muted)' }}
        >
          ← {payment.expertName}
        </Link>
        <h1 className="font-num mt-1 text-xl font-semibold tracking-tight tabular-nums">
          {formatPayout(payment.amount, payment.currency)}
        </h1>
        <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
          {payment.confirmed ? 'Confirmed by the expert' : 'Sent, awaiting acknowledgement'}
        </p>
      </header>

      {/* A `dl`, not a `div`: `dt`/`dd` are only meaningful inside one, and a screen reader
          announces these as the term/value pairs they are rather than as loose text. */}
      <dl
        className="grid gap-3 rounded-lg border p-4 sm:grid-cols-2"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
      >
        <Fact term="Expert" value={payment.expertName} />
        <Fact term="Sent" value={payment.paidDate.slice(0, 10)} />
        <Fact term="Method" value={payment.method} />
        <Fact term="Reference" value={payment.reference} />
        <Fact term="Recorded by" value={recordedByName} />
        <Fact term="Drafts covered" value={String(payment.draftCount)} />
        {notes && <Fact term="Notes" value={notes} />}
      </dl>

      <section
        className="rounded-lg border"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
      >
        <h2 className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: 'var(--border-default)' }}>
          What this transfer covered
        </h2>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
              <th className="px-4 py-2 font-medium">Case</th>
              <th className="px-4 py-2 font-medium">Status</th>
              <th className="px-4 py-2 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {drafts.map((draft) => (
              <tr key={draft.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                <td className="px-4 py-2">
                  <Link to={`/cases/${draft.caseId}`}>{draft.caseCode}</Link>
                </td>
                <td className="px-4 py-2">{draft.status.toLowerCase()}</td>
                <td className="font-num px-4 py-2 text-right tabular-nums">
                  {draft.amount === null ? '—' : formatPayout(draft.amount, draft.currency)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {refusal && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {refusal}
        </p>
      )}

      {mayRecord && !payment.confirmed && (
        <div>
          <button
            type="button"
            onClick={() => void confirm()}
            className="rounded-md px-3 py-1.5 text-sm font-medium text-white"
            style={{ background: 'var(--accent-primary)' }}
          >
            The expert confirmed receipt
          </button>
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            Terminal, and it confirms every draft above at once.
          </p>
        </div>
      )}
    </div>
  )
}

function Fact({ term, value }: { term: string; value: string }) {
  return (
    <div>
      <dt className="text-[11px] tracking-[0.06em] uppercase" style={{ color: 'var(--text-muted)' }}>
        {term}
      </dt>
      <dd className="mt-0.5 text-sm">{value}</dd>
    </div>
  )
}
