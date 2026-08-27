import { useCallback, useEffect, useMemo, useState } from 'react'
import { useMe } from '../../lib/authContext'
import { formatPayout } from '../../lib/money'
import PaymentForm from './PaymentForm'
import { fetchBatch } from './payoutApi'
import { mondayOf, weekLabel, type BatchView, type ExpertGroup } from './payoutRules'

/**
 * The week somebody works down on payout day.
 *
 * **One screen, not two.** The original design had a payout list and a separate "weekly batch"
 * view; they were the same rows, the same week and the same actions, so building both would
 * have been building it twice.
 *
 * Grouped by expert rather than listed flat, because the unit of payment is the transfer and
 * one transfer covers every draft that expert delivered — the grouping *is* the workflow. A
 * flat list would make the ENM do the addition that the server is about to refuse them for
 * getting wrong.
 *
 * Writes are hidden from anyone outside the three roles that may record a payout. The server
 * refuses them either way (`PayoutService.requireMayRecord`, re-checked below `@PreAuthorize`);
 * this only avoids offering a button that answers 403 — the Unit 10 lesson.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; view: BatchView }
  | { status: 'failed'; message: string }

const MAY_RECORD = ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER']

export default function PayoutBatch() {
  const me = useMe()
  const [weekOf, setWeekOf] = useState<string | null>(null)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [settling, setSettling] = useState<ExpertGroup | null>(null)

  const mayRecord = MAY_RECORD.includes(me.role)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState({ status: 'ready', view: await fetchBatch(weekOf, signal) })
      } catch (error: unknown) {
        // StrictMode double-invokes effects in dev and the cleanup aborts the first request,
        // so an abort is the normal path rather than a failure.
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load this week',
        })
      }
    },
    [weekOf],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  const view = state.status === 'ready' ? state.view : null

  // Remaining is derived here rather than sent: it is due minus paid by definition, and a
  // second figure the server also computes is a second thing that can disagree.
  const remaining = useMemo(() => (view ? view.due - view.paid : 0), [view])

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p
            className="text-[11px] font-semibold tracking-[0.08em] uppercase"
            style={{ color: 'var(--text-muted)' }}
          >
            Expert payouts
          </p>
          <h1 className="mt-1 text-xl font-semibold tracking-tight">
            {view ? weekLabel(view.weekStart, view.weekEnd) : 'Weekly payouts'}
          </h1>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Week of</span>
          {/* Native date input, not a picker library: the platform already ships one, and the
              value is normalised to that week's Monday so any day in a week finds its week. */}
          <input
            type="date"
            value={view?.weekStart ?? ''}
            onChange={(e) => setWeekOf(e.target.value ? mondayOf(e.target.value) : null)}
            className="rounded border px-2 py-1 text-sm"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>
      </header>

      {state.status === 'loading' && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading this week…
        </p>
      )}

      {state.status === 'failed' && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {state.message}
        </p>
      )}

      {view && (
        <>
          <section className="grid gap-3 sm:grid-cols-4">
            <Total label="Due this week" value={view.due} currency={view.currency} />
            <Total label="Paid" value={view.paid} currency={view.currency} />
            <Total label="Remaining" value={remaining} currency={view.currency} />
            <Total
              label="Overdue"
              value={view.overdue}
              currency={view.currency}
              token={view.overdue > 0 ? 'var(--status-red)' : undefined}
            />
          </section>

          {view.groups.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              Nothing is owed for this week.
            </p>
          ) : (
            <div className="flex flex-col gap-4">
              {view.groups.map((group) => (
                <Group
                  key={group.expertId}
                  group={group}
                  mayRecord={mayRecord}
                  onRecord={() => setSettling(group)}
                />
              ))}
            </div>
          )}
        </>
      )}

      {settling && (
        <PaymentForm
          group={settling}
          onCancel={() => setSettling(null)}
          onRecorded={() => {
            setSettling(null)
            void load()
          }}
        />
      )}
    </div>
  )
}

function Total({
  label,
  value,
  currency,
  token,
}: {
  label: string
  value: number
  currency: string
  token?: string
}) {
  return (
    <div
      className="rounded-lg border p-3"
      style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
    >
      <p className="text-[11px] tracking-[0.06em] uppercase" style={{ color: 'var(--text-muted)' }}>
        {label}
      </p>
      <p className="font-num mt-1 text-lg font-semibold tabular-nums" style={token ? { color: token } : undefined}>
        {formatPayout(value, currency)}
      </p>
    </div>
  )
}

function Group({
  group,
  mayRecord,
  onRecord,
}: {
  group: ExpertGroup
  mayRecord: boolean
  onRecord: () => void
}) {
  return (
    <section className="rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3" style={{ borderColor: 'var(--border-default)' }}>
        <div>
          <h2 className="text-sm font-semibold">{group.expertName}</h2>
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {group.drafts.length} {group.drafts.length === 1 ? 'draft' : 'drafts'}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="font-num text-sm font-semibold tabular-nums">
            {formatPayout(group.subtotal, group.currency)}
          </span>
          {mayRecord && (
            <button
              type="button"
              onClick={onRecord}
              className="rounded-md px-3 py-1.5 text-sm font-medium text-white"
              style={{ background: 'var(--accent-primary)' }}
            >
              Record payment
            </button>
          )}
        </div>
      </header>

      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
            <th className="px-4 py-2 font-medium">Case</th>
            <th className="px-4 py-2 font-medium">Due</th>
            <th className="px-4 py-2 text-right font-medium">Amount</th>
          </tr>
        </thead>
        <tbody>
          {group.drafts.map((draft) => (
            <tr key={draft.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
              <td className="px-4 py-2">{draft.caseCode}</td>
              <td className="px-4 py-2" style={draft.overdue ? { color: 'var(--status-red)' } : undefined}>
                {draft.dueDate.slice(0, 10)}
                {draft.overdue && ' · overdue'}
              </td>
              <td className="font-num px-4 py-2 text-right tabular-nums">
                {/* Null is not zero: nobody has decided this one yet, and it cannot be settled
                    until somebody does. Rendering it as an amount would hide that. */}
                {draft.amount === null ? (
                  <span style={{ color: 'var(--status-amber)' }}>not set</span>
                ) : (
                  formatPayout(draft.amount, draft.currency)
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
