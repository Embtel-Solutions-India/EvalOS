import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { formatPayout } from '../../lib/money'
import { fetchPayment } from './payoutApi'
import { PAYOUT_STATUS_LABEL } from './payoutRules'
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
 * **Only the expert confirms** (Unit 63), from their portal: every entry here is manual, so the
 * person who typed it confirming it would prove nothing. That turns Processing into Paid for every
 * draft the transfer settled.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; view: PaymentDetailView }
  | { status: 'failed'; message: string }

export default function PaymentDetail() {
  const { paymentId = '' } = useParams()
  const [state, setState] = useState<LoadState>({ status: 'loading' })

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
          {payment.confirmed
            ? 'Paid — the expert confirmed receiving it'
            : 'Processing — waiting for the expert to confirm receipt in their portal'}
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
                <td className="px-4 py-2">{PAYOUT_STATUS_LABEL[draft.status]}</td>
                <td className="font-num px-4 py-2 text-right tabular-nums">
                  {draft.amount === null ? '—' : formatPayout(draft.amount, draft.currency)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* No staff confirm (Unit 63): every entry is manual, so only the expert confirms. */}
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
