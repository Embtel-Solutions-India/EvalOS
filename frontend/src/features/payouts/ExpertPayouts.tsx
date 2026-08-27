import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { formatPayout } from '../../lib/money'
import { correctAmount, fetchPayments, fetchPayouts } from './payoutApi'
import type { LedgerRow, PaymentRow } from './payoutRules'

/**
 * One expert: what they are still owed, then what they have already been sent.
 *
 * Two reads rather than one, because they answer different questions and come from different
 * tables — pending drafts are ledger rows, history is payments. Putting them on one screen is
 * what lets somebody answer "why is this expert chasing us" without opening two.
 *
 * The pending list is the one place a draft's amount can be corrected. It is editable only
 * while `PENDING`: once a draft is settled its amount is part of a payment's sum, and changing
 * it would break that sum after the fact. The server refuses it either way.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; pending: LedgerRow[]; payments: PaymentRow[] }
  | { status: 'failed'; message: string }

const MAY_RECORD = ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER']

export default function ExpertPayouts() {
  const { expertId = '' } = useParams()
  const me = useMe()
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [editing, setEditing] = useState<string | null>(null)
  const [draftAmount, setDraftAmount] = useState('')
  const [refusal, setRefusal] = useState<string | null>(null)

  const mayRecord = MAY_RECORD.includes(me.role)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const [pending, payments] = await Promise.all([
          fetchPayouts({ expertId, status: 'PENDING' }, signal),
          fetchPayments(expertId, signal),
        ])
        setState({ status: 'ready', pending, payments })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load this expert',
        })
      }
    },
    [expertId],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  const save = async (payoutId: string) => {
    setRefusal(null)
    try {
      await correctAmount(payoutId, Number(draftAmount))
      setEditing(null)
      await load()
    } catch (error: unknown) {
      setRefusal(error instanceof Error ? error.message : 'The amount was not saved')
    }
  }

  const expertName =
    state.status === 'ready'
      ? (state.pending[0]?.expertName ?? state.payments[0]?.expertName ?? 'Expert')
      : 'Expert'

  return (
    <div className="flex flex-col gap-5">
      <header>
        <Link to="/payouts" className="text-xs" style={{ color: 'var(--text-muted)' }}>
          ← Weekly payouts
        </Link>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">{expertName}</h1>
      </header>

      {state.status === 'loading' && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading…
        </p>
      )}

      {state.status === 'failed' && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {state.message}
        </p>
      )}

      {state.status === 'ready' && (
        <>
          <section
            className="rounded-lg border"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          >
            <h2 className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: 'var(--border-default)' }}>
              Pending drafts
            </h2>
            {state.pending.length === 0 ? (
              <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>
                Nothing outstanding.
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                    <th className="px-4 py-2 font-medium">Case</th>
                    <th className="px-4 py-2 font-medium">Due</th>
                    <th className="px-4 py-2 text-right font-medium">Amount</th>
                    {mayRecord && <th className="px-4 py-2" />}
                  </tr>
                </thead>
                <tbody>
                  {state.pending.map((draft) => (
                    <tr key={draft.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                      <td className="px-4 py-2">
                        <Link to={`/cases/${draft.caseId}`}>{draft.caseCode}</Link>
                      </td>
                      <td
                        className="px-4 py-2"
                        style={draft.overdue ? { color: 'var(--status-red)' } : undefined}
                      >
                        {draft.dueDate.slice(0, 10)}
                        {draft.overdue && ' · overdue'}
                      </td>
                      <td className="font-num px-4 py-2 text-right tabular-nums">
                        {editing === draft.id ? (
                          <input
                            type="number"
                            step="0.01"
                            min="0"
                            value={draftAmount}
                            onChange={(e) => setDraftAmount(e.target.value)}
                            className="w-24 rounded border px-2 py-1 text-right"
                            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-base)' }}
                          />
                        ) : draft.amount === null ? (
                          <span style={{ color: 'var(--status-amber)' }}>not set</span>
                        ) : (
                          formatPayout(draft.amount, draft.currency)
                        )}
                      </td>
                      {mayRecord && (
                        <td className="px-4 py-2 text-right">
                          {editing === draft.id ? (
                            <button type="button" onClick={() => void save(draft.id)} className="text-xs underline">
                              Save
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => {
                                setEditing(draft.id)
                                setDraftAmount(draft.amount === null ? '' : String(draft.amount))
                              }}
                              className="text-xs underline"
                            >
                              {draft.amount === null ? 'Set amount' : 'Edit'}
                            </button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {refusal && (
              <p className="px-4 py-2 text-sm" style={{ color: 'var(--status-red)' }}>
                {refusal}
              </p>
            )}
          </section>

          <section
            className="rounded-lg border"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          >
            <h2 className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: 'var(--border-default)' }}>
              Payment history
            </h2>
            {state.payments.length === 0 ? (
              <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>
                Nothing has been sent yet.
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                    <th className="px-4 py-2 font-medium">Sent</th>
                    <th className="px-4 py-2 font-medium">Method</th>
                    <th className="px-4 py-2 font-medium">Reference</th>
                    <th className="px-4 py-2 font-medium">Drafts</th>
                    <th className="px-4 py-2 text-right font-medium">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {state.payments.map((payment) => (
                    <tr key={payment.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                      <td className="px-4 py-2">
                        <Link to={`/payouts/payments/${payment.id}`}>{payment.paidDate.slice(0, 10)}</Link>
                      </td>
                      <td className="px-4 py-2">{payment.method}</td>
                      <td className="px-4 py-2">{payment.reference}</td>
                      <td className="font-num px-4 py-2 tabular-nums">{payment.draftCount}</td>
                      <td className="font-num px-4 py-2 text-right tabular-nums">
                        {formatPayout(payment.amount, payment.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  )
}
