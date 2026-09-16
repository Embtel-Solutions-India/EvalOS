import { Card, KpiCard } from '../../components/ui/card'
import { useMe } from '../../lib/authContext'
import { mayReach } from '../shell/navigation'
import { useFilters } from '../shell/filtersContext'
import { fetchPmMetrics, fetchRevenueMetrics, type PmMetrics, type RevenueMetrics } from './pmMetricsApi'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * The GM's and Brand Manager's screen: the money, and the operational health behind it.
 *
 * Largest tile is open liability, not collected: money taken for work not yet delivered is the
 * figure that carries risk, and invariant 5 is what makes it meaningful.
 *
 * The GM sees a per-brand breakdown and the Brand Manager does not — theirs would be the same
 * number printed twice.
 *
 * <p><strong>The operational row was added 2026-09-14, and the reason is worth keeping.</strong>
 * This screen used to be four money figures and a by-brand table, with <em>no clickable tile
 * anywhere</em> — so the two most senior roles landed on a finance summary they could not act on,
 * while every operational figure they needed was already being computed one directory over for
 * the PM. To find a case in trouble a GM opened the board and scanned eight columns by eye.
 *
 * <p><strong>It needs no new endpoint.</strong> `/api/metrics/pm` is already gated
 * `GM, BRAND_MANAGER, PROJECT_MANAGER`, so this composes two reads the caller may already make.
 * A third metrics endpoint would have been a new surface to secure for figures that exist.
 *
 * <p><strong>Every tile that counts something links to the list behind it</strong>, and only to
 * somewhere this role can actually reach — the GM's nav has no `/inbox`, so `at risk` drills into
 * `/board?urgent=1` (the board's own existing predicate) rather than the PM's queue.
 */
export default function RevenueDashboard() {
  const me = useMe()
  const { activeBrandId, dateRange } = useFilters()

  /**
   * A tile links only where this reader can actually go.
   *
   * <p>This screen serves the GM **and** the Brand Manager, and their nav is not the same:
   * `/delivery` is `['GM', 'PROJECT_COORDINATOR']`, so a Brand Manager following a "Delivered"
   * tile would land on `Forbidden`. Asked through `mayReach` — the same table that filters the
   * nav and guards the router — rather than by listing roles here, because a third caller of the
   * role list is a third thing to keep in step.
   */
  const linkIfReachable = (path: string): string | undefined =>
    mayReach(me.role, path.split('?')[0]) ? path : undefined
  const { data, state } = useMetrics<RevenueMetrics>(
    (signal) => fetchRevenueMetrics(activeBrandId, signal),
    [activeBrandId],
  )

  // A second, independent read. Deliberately not folded into the one above: the money figures are
  // the headline and must render even if this fails, so the two carry their own states.
  const { data: ops, state: opsState } = useMetrics<PmMetrics>(
    (signal) => fetchPmMetrics(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  )

  const total = data?.total
  const capacity = (ops?.workload ?? []).reduce(
    (acc, row) => ({ active: acc.active + row.active, capacity: acc.capacity + row.capacity }),
    { active: 0, capacity: 0 },
  )

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Money in vs delivered</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {activeBrandId ? 'one brand' : 'all brands'}
        </p>
      </header>

      {/* If the three ever stop adding up the screen says so rather than showing them anyway.
          Three numbers that quietly disagree are worse than an error. */}
      {total && !total.reconciles && (
        <p
          className="mt-4 rounded-lg border p-3 text-sm"
          style={{
            background: 'var(--status-red-bg)',
            borderColor: 'var(--status-red)',
            color: 'var(--status-red)',
          }}
        >
          Collected does not equal recognised plus open liability. These figures are inconsistent —
          do not report from them until it is investigated.
        </p>
      )}

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Open liability"
          wide
          money
          state={state}
          value={total ? round(total.openLiability) : null}
          denominator={data ? `${data.openCases} cases paid and not yet delivered` : undefined}
          tone={data === null ? undefined : data.openCases > 0 ? 'warn' : 'good'}
          note="Money taken for work not yet delivered — the refund exposure."
        />

        <KpiCard title="Collected" money state={state} value={total ? round(total.collected) : null} />
        <KpiCard
          title="Recognised"
          money
          state={state}
          value={total ? round(total.recognized) : null}
          tone="good"
          note="Paid and delivered — invariant 5."
        />
        <KpiCard
          title="Refunded"
          money
          state={state}
          value={total ? round(total.refunded) : null}
          tone={total && total.refunded > 0 ? 'bad' : undefined}
          note="Shown beside the others and counted inside none of them."
        />

        <Card
          title="By brand"
          wide
          state={emptyWhen(state, (data?.perBrand.length ?? 0) === 0, 'One brand in scope.')}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">Brand</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Open</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Recognised</th>
              </tr>
            </thead>
            <tbody>
              {data?.perBrand.map((row) => (
                <tr key={row.brandId}>
                  <td className="py-1">{row.name}</td>
                  <td className="font-num py-1 text-right tabular-nums">{round(row.money.openLiability)}</td>
                  <td className="font-num py-1 text-right tabular-nums">{round(row.money.recognized)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        {/* Was `{ kind: 'unavailable', blockedBy: 'Unit 16' }` until 2026-09-14 — a tombstone.
            Unit 16 shipped: `/payouts` is in this role's own nav two rows down, so the screen was
            telling a GM the data did not exist while a nav entry served it. `unavailable` is for
            a unit that has not shipped; spending it on one that has teaches people to ignore the
            state entirely. */}
        <Card
          title="Money out"
          state={{ kind: 'ok' }}
          to={linkIfReachable('/payouts')}
          note="Expert payouts — the other half of the P&L."
        >
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            Open the payout ledger to see what the network is owed and what has settled.
          </p>
        </Card>
      </div>

      {/* The operational row. Money says what is at stake; this says what is going wrong, and
          every countable tile drills into the list behind it. */}
      <h2 className="mt-8 text-lg font-semibold tracking-tight">Production health</h2>

      <div className="mt-3 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="At risk now"
          state={opsState}
          value={ops ? ops.atRiskNow : null}
          tone={ops === null ? undefined : ops.atRiskNow > 0 ? 'bad' : 'good'}
          to={linkIfReachable('/board?urgent=1')}
          note="Cases at risk or already overdue against their stage SLA."
        />

        <KpiCard
          title="Unassigned"
          state={opsState}
          value={ops ? ops.unassigned : null}
          tone={ops === null ? undefined : ops.unassigned > 0 ? 'warn' : 'good'}
          to={linkIfReachable('/board')}
          note="Paid work with nobody on it."
        />

        <KpiCard
          title="Delivered"
          state={opsState}
          value={ops ? ops.onTime.delivered : null}
          denominator={
            ops && ops.onTime.ratePct !== null ? `${ops.onTime.ratePct}% on time` : undefined
          }
          tone={ops?.onTime.ratePct === null ? undefined : (ops?.onTime.ratePct ?? 0) >= 90 ? 'good' : 'warn'}
          to={linkIfReachable('/delivery')}
          note="In the selected period."
        />

        <KpiCard
          title="Team load"
          state={opsState}
          value={capacity.capacity > 0 ? Math.round((capacity.active / capacity.capacity) * 100) : null}
          unit="%"
          denominator={
            capacity.capacity > 0 ? `${capacity.active} of ${capacity.capacity} slots` : undefined
          }
          tone={
            capacity.capacity === 0
              ? undefined
              : capacity.active / capacity.capacity >= 0.9
                ? 'bad'
                : capacity.active / capacity.capacity >= 0.75
                  ? 'warn'
                  : 'good'
          }
          note="Active cases against case-manager capacity."
        />
      </div>
    </section>
  )
}

/** Whole units. These are headline figures, and the cents add noise without adding meaning. */
function round(value: number): number {
  return Math.round(value)
}
