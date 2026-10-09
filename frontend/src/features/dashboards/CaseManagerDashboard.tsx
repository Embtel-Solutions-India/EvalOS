import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, NotebookPen } from 'lucide-react'
import { Card, KpiCard } from '../../components/ui/card'
import { SheetContent, SheetRoot, SheetTrigger } from '../../components/ui/dialog'
import { riskColor, riskLabel } from '../queues/queueRules'
import FlagToPmDialog from './FlagToPmDialog'
import { ChecklistProgress } from './ChecklistProgress'
import { ExpertOffers } from './ExpertOffers'
import {
  fetchCaseManagerMetrics,
  fetchCaseManagerWork,
  type CaseManagerMetrics,
  type CaseManagerWork,
  type MyCase,
} from './pmMetricsApi'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * One Case Manager's docket, built to the CRM spec's "Case Manager sees on dashboard".
 *
 * The spec asks for **lists**, not only counts — my active cases with the client, product,
 * deadline, PM notes, stage and expert; a priority queue; the draft lifecycle; the client
 * feedback log; and expert signing shown per case with a prompt when it goes overdue. The server sends the docket
 * already deadline-ordered, so **the priority queue is this list** rather than a second one that
 * could disagree with it about the same case.
 *
 * No brand filter: the caller is the scope, so the shell's brand switcher does not apply.
 */
export default function CaseManagerDashboard() {
  const { data, state, reload } = useMetrics<CaseManagerMetrics>(
    (signal) => fetchCaseManagerMetrics(signal),
    [],
  )

  // A separate load, so a failed /work cannot blank the tiles below it or the reverse.
  const { data: work, state: workState } = useMetrics<CaseManagerWork>(
    (signal) => fetchCaseManagerWork(signal),
    [],
  )

  return (
    <section>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">My cases</h1>
      </header>

      <p className="mt-4 text-xs" style={{ color: 'var(--text-muted)' }}>
        Right now — what is holding your open cases up.
      </p>
      <div className="mt-2 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Returned by PM"
          state={workState}
          value={work?.drafts.returned ?? null}
          denominator="cases"
          tone={work === null ? undefined : work.drafts.returned > 0 ? 'warn' : 'good'}
        />
        <KpiCard
          title="Checklist blockers"
          state={workState}
          value={work?.checklist.blockerItems ?? null}
          denominator={work ? `items missing or incorrect, on ${work.checklist.blockerCases} cases` : undefined}
          tone={work === null ? undefined : work.checklist.blockerItems > 0 ? 'bad' : 'good'}
        />
        <KpiCard
          title="Open expert offers"
          state={workState}
          value={work?.offers.open ?? null}
          denominator="offers waiting for an answer"
        />
        <KpiCard
          title="Rematch needed"
          state={workState}
          value={work?.offers.rematch ?? null}
          denominator="cases whose expert declined"
          tone={work === null ? undefined : work.offers.rematch > 0 ? 'bad' : 'good'}
        />
      </div>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Due now"
          state={state}
          value={data?.critical ?? null}
          denominator={data ? `${data.atRisk} more inside 48h · ${data.active} active` : undefined}
          tone={data === null ? undefined : data.critical > 0 ? 'bad' : data.atRisk > 0 ? 'warn' : 'good'}
          note="Red deadline band: past the date or inside 24 business hours. Zero critical is the daily goal."
        />

        <KpiCard
          title="Completed on time"
          state={state}
          value={data?.deliveredOnTimePct ?? null}
          unit="%"
          denominator={data ? `of ${data.delivered} delivered` : undefined}
          tone={
            data === null || data.delivered === 0
              ? undefined
              : data.deliveredOnTimePct >= 90
                ? 'good'
                : data.deliveredOnTimePct >= 75
                  ? 'warn'
                  : 'bad'
          }
        />

        <KpiCard
          title="Draft revision rate"
          state={state}
          value={data?.revisionRatePct ?? null}
          unit="%"
          denominator={data?.comparable ? 'flagged above 30%' : 'too few cases to compare'}
          // The spec's own threshold, and only ever coloured once the sample supports it: an
          // amber number over four cases is a judgement the data cannot carry.
          tone={data === null || !data.comparable ? undefined : data.revisionRateFlagged ? 'warn' : 'good'}
          note="How often the PM returns a first draft."
        />

        <KpiCard
          title="Client revision requests"
          state={state}
          value={data?.clientRevisionRatePct ?? null}
          unit="%"
          denominator={data?.comparable ? 'of your cases' : 'too few cases to compare'}
          note="Cases the client came back on. Counted from when this became its own audit action."
        />

        <Card
          title="Priority queue"
          wide
          state={emptyWhen(state, data?.cases.length === 0, 'You have no open cases.')}
          note="Soonest deadline first. Open a case to work it."
        >
          <div className="max-h-96 overflow-y-auto">
            <ul className="space-y-1.5">
              {data?.cases.map((row) => (
                <CaseRow key={row.id} row={row} onFlagged={reload} />
              ))}
            </ul>
          </div>
        </Card>

        <Card
          title="Client feedback"
          wide
          state={emptyWhen(state, data?.clientFeedback.length === 0, 'No client has asked for changes.')}
          note="What clients asked to be changed, newest first."
        >
          <ul className="max-h-56 space-y-2 overflow-y-auto">
            {data?.clientFeedback.map((entry) => (
              <li key={`${entry.caseId}-${entry.at}`} className="text-sm">
                <div className="flex items-baseline justify-between gap-2">
                  <Link to={`/cases/${entry.caseId}`} className="font-mono text-xs font-medium">
                    {entry.caseCode}
                  </Link>
                  <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                    {new Date(entry.at).toLocaleDateString()}
                  </span>
                </div>
                <p style={{ color: 'var(--text-muted)' }}>
                  {entry.note ?? 'No reason was recorded with the request.'}
                </p>
              </li>
            ))}
          </ul>
        </Card>

        <ChecklistProgress cases={work?.checklist.cases} state={workState} />
        <ExpertOffers rows={work?.offers.rows} state={workState} />
        <Card title="Draft lifecycle" state={workState} note="Your open cases by where the draft sits.">
          {work && (
            <dl className="grid grid-cols-3 gap-3">
              <Figure label="Before drafting" value={work.drafts.beforeDraft} />
              <Figure label="Drafting" value={work.drafts.drafting} />
              <Figure
                label="Returned by PM"
                value={work.drafts.returned}
                tone={work.drafts.returned > 0 ? 'var(--status-amber)' : undefined}
              />
              <Figure label="With the PM" value={work.drafts.withPm} />
              <Figure label="With the client" value={work.drafts.withClient} />
              <Figure label="Approved onward" value={work.drafts.approved} />
            </dl>
          )}
        </Card>
      </div>
    </section>
  )
}

function CaseRow({ row, onFlagged }: { row: MyCase; onFlagged: () => void }) {
  const [flagOpen, setFlagOpen] = useState(false)

  return (
    <li
      className="rounded-md border p-2.5"
      style={{
        borderColor: row.signingOverdue ? 'var(--status-red)' : 'var(--border-default)',
        background: 'var(--bg-surface)',
      }}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span
          className="h-2 w-2 shrink-0 rounded-full"
          style={{ background: riskColor(row.deadlineRisk) }}
          aria-hidden
        />
        <span className="sr-only">{riskLabel(row.deadlineRisk)}</span>

        <Link to={`/cases/${row.id}`} className="text-sm font-medium">
          {row.clientName ?? 'Unnamed contact'}
        </Link>
        <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {readable(row.serviceType)} · {readable(row.stage)}
        </span>

        <span className="font-num ml-auto text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {row.deadline ? new Date(row.deadline).toLocaleDateString() : 'no deadline'}
        </span>

        {/* The spec's notes panel, per case rather than as one blob — the notes are about a
            specific case and reading them beside the wrong one is worse than not reading them. */}
        {row.strategyNotes && (
          <SheetRoot>
            <SheetTrigger asChild>
              <button
                type="button"
                className="inline-flex items-center gap-1 text-xs font-medium"
                style={{ color: 'var(--accent-primary)' }}
              >
                <NotebookPen className="h-3.5 w-3.5" aria-hidden />
                PM notes
              </button>
            </SheetTrigger>
            <SheetContent
              title={`PM strategy notes — ${row.caseCode}`}
              description="Written by the Project Manager. You read these; the PM edits them."
            >
              <p className="text-sm whitespace-pre-wrap">{row.strategyNotes}</p>
            </SheetContent>
          </SheetRoot>
        )}
      </div>

      {row.signingOverdue && (
        <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--status-red)' }} aria-hidden />
          <span style={{ color: 'var(--status-red)' }}>
            {row.expertName ?? 'The expert'} has not signed inside 24 hours.
          </span>
          {/* The spec asks for a "reassign prompt". Reassignment is PM/ENM-gated, so what the CM
              is offered is the escalation they actually hold — anything else would render a
              button the server refuses. */}
          <button
            type="button"
            onClick={() => setFlagOpen(true)}
            className="font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Flag to the PM
          </button>
          <FlagToPmDialog
            caseId={row.id}
            caseCode={row.caseCode}
            open={flagOpen}
            onOpenChange={setFlagOpen}
            onFlagged={onFlagged}
          />
        </div>
      )}
    </li>
  )
}

function Figure({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div>
      <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd
        className="font-num text-2xl font-semibold tabular-nums"
        style={{ color: tone ?? 'var(--text-primary)' }}
      >
        {value}
      </dd>
    </div>
  )
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
