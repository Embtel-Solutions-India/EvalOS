import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/card'
import { useMetrics } from './useMetrics'
import {
  fetchPortalLinkLedger,
  type LinkState,
  type PortalLinkRow,
} from './pmMetricsApi'

/**
 * Which portal links exist, whether anyone opened them, and whether a clock is running against
 * one nobody has — gap **G16**.
 *
 * **This is a safety net for a channel that does not exist, not a report.** EvalOS sends no mail
 * (invariant 14), so a link reaches its recipient because a staff member copied it out of the
 * case's link panel and sent it by hand. Nothing records that they did, and until this tile
 * nothing noticed when they had not.
 *
 * That is survivable for a client, who waits. For an expert it is **gap G15**: the 20h/24h
 * signing clock runs whether or not they ever received their link, so **the likeliest way EvalOS
 * breaches that SLA is a link nobody sent** — and no screen showed it.
 *
 * **Every band comes from the server.** This file maps `RED`/`AMBER`/`GREEN` to a colour token
 * and does not re-derive one: the rule is "is a clock running against a link nobody opened",
 * and it is answered next to the SLA calculator so this tile and the board's rail cannot
 * disagree about one case.
 *
 * **Green rows are hidden by default.** Most rows are green — an absent link is the correct
 * state almost everywhere — and a ledger that lists every case is one nobody scrolls.
 */
export default function PortalLinkLedger() {
  const { data, state } = useMetrics((signal) => fetchPortalLinkLedger(signal), [])

  const needAttention = (data?.rows ?? []).filter((row) => row.state !== 'GREEN')

  return (
    <Card
      title="Portal links"
      note={
        data
          ? data.red + data.amber === 0
            ? 'Every link that is needed is out and opened.'
            : `${data.red} need attention now, ${data.amber} soon.`
          : undefined
      }
      state={state}
      wide
    >
      {data && needAttention.length === 0 ? (
        <p className="p-4 text-sm text-slate-500">
          {/*
            Said explicitly rather than shown as an empty table. "Nothing here" and "nothing
            loaded" look identical, and this tile is one somebody checks precisely when they
            are worried.
          */}
          Nothing outstanding. Links are only listed here when a stage is waiting on one.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-500">
                <th className="py-2 pr-3">Case</th>
                <th className="py-2 pr-3">Stage</th>
                <th className="py-2 pr-3">For</th>
                <th className="py-2 pr-3">Opened</th>
                <th className="py-2 pr-3">Expires</th>
                <th className="py-2 pr-3">Re-sent</th>
              </tr>
            </thead>
            <tbody>
              {needAttention.map((row) => (
                <LedgerRow key={`${row.caseId}-${row.audience}`} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

const BAND: Record<LinkState, string> = {
  RED: 'var(--status-red)',
  AMBER: 'var(--status-amber)',
  GREEN: 'var(--status-green)',
}

function LedgerRow({ row }: { row: PortalLinkRow }) {
  return (
    <tr className="border-b border-slate-100 last:border-0">
      <td className="py-2 pr-3">
        {/*
          Links to the case, which is where the timeline answers "who issued this link" — the
          ledger deliberately has no issuer column, because the mint's audit row is about the
          case rather than the token and matching them would mean parsing a note.
        */}
        <Link to={`/cases/${row.caseId}`} className="font-medium text-slate-900 underline">
          {row.caseCode ?? '—'}
        </Link>
      </td>
      <td className="py-2 pr-3 text-slate-600">{row.stage.replace(/_/g, ' ').toLowerCase()}</td>
      <td className="py-2 pr-3">
        <span
          className="inline-flex items-center gap-1.5 text-slate-700"
          style={{ color: BAND[row.state] }}
        >
          <span
            aria-hidden
            className="inline-block h-2 w-2 rounded-full"
            style={{ backgroundColor: BAND[row.state] }}
          />
          {row.audience === 'CLIENT' ? 'Client' : 'Expert'}
        </span>
      </td>
      <td className="py-2 pr-3 text-slate-600">
        {/*
          Three distinct states, and collapsing any two would hide the thing this tile is for:
          no live link at all is not the same as one nobody opened.
        */}
        {!row.live ? 'no live link' : row.openedAt ? new Date(row.openedAt).toLocaleDateString() : 'never'}
      </td>
      <td className="py-2 pr-3 text-slate-600">
        {row.expiresAt ? new Date(row.expiresAt).toLocaleDateString() : '—'}
      </td>
      {/* Blank rather than 0: a zero column of noise across every row helps nobody. */}
      <td className="py-2 pr-3 text-slate-600">{row.reMints > 0 ? row.reMints : ''}</td>
    </tr>
  )
}
