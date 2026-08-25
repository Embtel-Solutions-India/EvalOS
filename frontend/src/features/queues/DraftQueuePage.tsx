import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Check, ExternalLink, Eye, X } from 'lucide-react'
import { KpiCard } from '../../components/ui/card'
import { performAction } from '../board/boardApi'
import { QUICK_ACTIONS } from '../board/boardRules'
import { fetchTimeline, type TimelineEntry } from '../case/caseApi'
import { useFilters } from '../shell/filtersContext'
import { riskColor } from './queueRules'
import {
  DRAFT_TABS,
  fetchDraftReview,
  priorityLabel,
  statusTone,
  type DraftReview,
  type DraftRow,
  type DraftStatus,
} from './draftReviewApi'
import { DialogContent, DialogRoot } from '../../components/ui/dialog'

const APPROVE = QUICK_ACTIONS.find((action) => action.path === 'draft/pm-approve')!
const RETURN = QUICK_ACTIONS.find((action) => action.path === 'draft/pm-return')!

/**
 * The draft review workspace: the queue on the left, the draft under inspection on the right.
 *
 * **A split view rather than a modal**, because reviewing is a sweep: you open one, act, and move
 * to the next. A dialog per draft would close the queue each time and lose your place in it.
 *
 * Every figure and every row comes from `/api/metrics/drafts` — the caller's already-scoped read,
 * so this screen can never list a draft its reader could not open.
 */
export default function DraftQueuePage() {
  const { activeBrandId } = useFilters()
  const [data, setData] = useState<DraftReview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<DraftStatus | 'ALL'>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = () => {
    const controller = new AbortController()
    setError(null)
    fetchDraftReview(activeBrandId, controller.signal)
      .then(setData)
      .catch((cause: Error) => {
        if (!controller.signal.aborted) setError(cause.message)
      })
    return () => controller.abort()
  }

  useEffect(load, [activeBrandId])

  const rows = useMemo(
    () => (data ? data.rows.filter((row) => tab === 'ALL' || row.status === tab) : []),
    [data, tab],
  )
  const selected = rows.find((row) => row.id === selectedId) ?? null

  const summary = data?.summary
  const state = error ? ({ kind: 'error', note: error } as const) : data ? ({ kind: 'ok' } as const) : ({ kind: 'loading' } as const)

  return (
    <section>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Draft review</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
          Review drafts before they go to the client, and approve the ones that are ready.
        </p>
      </header>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
        <KpiCard title="Total drafts" state={state} value={summary?.total ?? null} />
        <KpiCard
          title="Pending review"
          state={state}
          value={summary?.pendingReview ?? null}
          tone={summary && summary.pendingReview > 0 ? 'warn' : 'good'}
          denominator={share(summary?.pendingReview, summary?.total)}
        />
        <KpiCard
          title="Revisions requested"
          state={state}
          value={summary?.revisionsRequested ?? null}
          tone={summary && summary.revisionsRequested > 0 ? 'bad' : 'good'}
          denominator={share(summary?.revisionsRequested, summary?.total)}
        />
        <KpiCard
          title="Ready for QC"
          state={state}
          value={summary?.readyForQc ?? null}
          denominator={share(summary?.readyForQc, summary?.total)}
        />
        <KpiCard
          title="Avg draft age"
          state={state}
          value={summary?.avgDraftAgeHours ?? null}
          unit="h"
          denominator="business hours, pending only"
        />
        <KpiCard
          title="SLA compliance"
          // Null is the empty state, not a zero: nothing pending is not 0% compliance.
          state={
            state.kind === 'ok' && summary?.slaCompliancePct === null
              ? { kind: 'empty', note: 'No draft is waiting.' }
              : state
          }
          value={summary?.slaCompliancePct ?? null}
          unit="%"
          tone={
            summary?.slaCompliancePct == null
              ? undefined
              : summary.slaCompliancePct >= 90
                ? 'good'
                : summary.slaCompliancePct >= 75
                  ? 'warn'
                  : 'bad'
          }
          denominator="inside the 12h review budget"
        />
      </div>

      <div className="mt-4 flex flex-wrap gap-1.5" role="tablist" aria-label="Draft status">
        {DRAFT_TABS.map((entry) => {
          const active = entry.status === tab
          const count =
            entry.status === 'ALL'
              ? data?.rows.length
              : data?.rows.filter((row) => row.status === entry.status).length
          return (
            <button
              key={entry.status}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setTab(entry.status)}
              className="h-9 px-3 text-sm"
              style={{
                borderRadius: 'var(--radius-md)',
                background: active ? 'var(--accent-soft)' : 'var(--bg-surface)',
                border: `1px solid ${active ? 'var(--accent-primary)' : 'var(--border-default)'}`,
                color: active ? 'var(--accent-primary)' : 'var(--text-primary)',
                fontWeight: active ? 500 : 400,
              }}
            >
              {entry.label}
              {count !== undefined && (
                <span className="font-num ml-1.5 tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {count}
                </span>
              )}
            </button>
          )
        })}
      </div>

      <div className="mt-4 flex flex-col gap-4 xl:flex-row">
        <div className="min-w-0 flex-1">
          {error && (
            <p className="text-sm" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}

          {data === null && !error && (
            <div className="h-64 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
          )}

          {data && rows.length === 0 && (
            <p
              className="rounded-lg border p-6 text-sm"
              style={{
                background: 'var(--bg-surface)',
                borderColor: 'var(--border-default)',
                color: 'var(--text-muted)',
              }}
            >
              {tab === 'ALL' ? 'No draft has been submitted yet.' : 'Nothing is in this state.'}
            </p>
          )}

          {data && rows.length > 0 && (
            <div
              className="scroll-slim overflow-x-auto rounded-lg border"
              style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
            >
              <table className="w-full text-sm">
                <thead>
                  <tr style={{ color: 'var(--text-muted)' }}>
                    <Th>Case / client</Th>
                    <Th>Report type</Th>
                    <Th>Expert</Th>
                    <Th>Draft updated</Th>
                    <Th>Status</Th>
                    <Th>Priority</Th>
                    <Th> </Th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <Row
                      key={row.id}
                      row={row}
                      selected={row.id === selectedId}
                      onSelect={() => setSelectedId(row.id)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {selected && (
          <DraftDetail
            row={selected}
            onClose={() => setSelectedId(null)}
            onActed={() => {
              setSelectedId(null)
              load()
            }}
          />
        )}
      </div>
    </section>
  )
}

function share(part: number | undefined, total: number | undefined): string | undefined {
  if (part === undefined || !total) return undefined
  return `${Math.round((part * 100) / total)}% of total`
}

function Th({ children }: { children: React.ReactNode }) {
  return (
    <th
      className="px-3 py-2 text-left text-xs font-medium tracking-wide uppercase"
      style={{ borderBottom: '1px solid var(--border-default)' }}
    >
      {children}
    </th>
  )
}

function Row({ row, selected, onSelect }: { row: DraftRow; selected: boolean; onSelect: () => void }) {
  const tone = statusTone(row.status)
  return (
    <tr
      style={{
        borderBottom: '1px solid var(--border-default)',
        background: selected ? 'var(--accent-soft)' : undefined,
      }}
    >
      <td className="px-3 py-2">
        <Link to={`/cases/${row.id}`} className="font-mono text-xs font-medium">
          {row.caseCode}
        </Link>
        <div className="truncate">{row.clientName ?? 'Unnamed contact'}</div>
      </td>
      <td className="px-3 py-2" style={{ color: 'var(--text-muted)' }}>
        {readable(row.serviceType)}
      </td>
      <td className="px-3 py-2">{row.expertName ?? 'Not assigned'}</td>
      <td className="font-num px-3 py-2 tabular-nums" style={{ color: 'var(--text-muted)' }}>
        {row.draftUpdated ? new Date(row.draftUpdated).toLocaleString() : '—'}
      </td>
      <td className="px-3 py-2">
        <span
          className="rounded-md px-2 py-0.5 text-xs font-medium"
          style={{ background: tone.bg, color: tone.fg }}
        >
          {tone.label}
        </span>
      </td>
      <td className="px-3 py-2">
        <span className="inline-flex items-center gap-1.5">
          <span
            className="h-2 w-2 shrink-0 rounded-full"
            style={{ background: riskColor(row.priority) }}
            aria-hidden
          />
          {priorityLabel(row.priority)}
        </span>
      </td>
      <td className="px-3 py-2 text-right">
        <button
          type="button"
          onClick={onSelect}
          aria-label={`Inspect ${row.caseCode}`}
          className="rounded-md p-1"
          style={{ color: 'var(--accent-primary)' }}
        >
          <Eye className="h-4 w-4" aria-hidden />
        </button>
      </td>
    </tr>
  )
}

/** The inspector. Loads the case's real activity trail on open rather than for every row. */
function DraftDetail({
  row,
  onClose,
  onActed,
}: {
  row: DraftRow
  onClose: () => void
  onActed: () => void
}) {
  const [activity, setActivity] = useState<TimelineEntry[] | null>(null)
  const [returning, setReturning] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setActivity(null)
    fetchTimeline(row.id, controller.signal)
      .then(setActivity)
      .catch(() => setActivity([]))
    return () => controller.abort()
  }, [row.id])

  const tone = statusTone(row.status)
  const reviewable = row.status === 'PENDING_REVIEW'

  async function run(action: typeof APPROVE, body?: Record<string, string>) {
    setBusy(true)
    setError(null)
    try {
      await performAction(row.id, action, body ?? {})
      onActed()
    } catch (cause) {
      setError((cause as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <aside
      className="w-full shrink-0 self-start rounded-lg border p-4 xl:w-80"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
      aria-label={`Draft detail for ${row.caseCode}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-mono text-xs font-medium">{row.caseCode}</p>
          <p className="truncate text-sm font-semibold">{row.clientName ?? 'Unnamed contact'}</p>
        </div>
        <span
          className="shrink-0 rounded-md px-2 py-0.5 text-xs font-medium"
          style={{ background: tone.bg, color: tone.fg }}
        >
          {tone.label}
        </span>
        <button type="button" onClick={onClose} aria-label="Close" style={{ color: 'var(--text-muted)' }}>
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      <dl className="mt-3 space-y-1.5 text-sm">
        <Field label="Expert" value={row.expertName ?? 'Not assigned'} />
        <Field label="Report type" value={readable(row.serviceType)} />
        <Field label="Draft version" value={`v${row.draftVersionCount}`} />
        <Field
          label="Deadline"
          value={
            row.deadline
              ? `${new Date(row.deadline).toLocaleDateString()}${
                  row.daysLeft === null
                    ? ''
                    : row.daysLeft >= 0
                      ? ` (${row.daysLeft} days left)`
                      : ` (${Math.abs(row.daysLeft)} days over)`
                }`
              : 'Not set'
          }
        />
      </dl>

      <div className="mt-4">
        <div className="flex items-baseline justify-between">
          <h3 className="text-xs font-semibold tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
            Progress
          </h3>
          <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {row.milestonesComplete}/{row.milestones.length}
          </span>
        </div>
        <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
          <div
            className="h-full rounded-md"
            style={{
              width: `${(row.milestonesComplete / row.milestones.length) * 100}%`,
              background: 'var(--accent-primary)',
            }}
          />
        </div>
        <ul className="mt-2 space-y-1">
          {row.milestones.map((step) => (
            <li key={step.label} className="flex items-center gap-2 text-sm">
              <Check
                className="h-3.5 w-3.5 shrink-0"
                style={{ color: step.done ? 'var(--status-green)' : 'var(--border-default)' }}
                aria-hidden
              />
              <span style={{ color: step.done ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                {step.label}
              </span>
              <span className="sr-only">{step.done ? 'done' : 'not done'}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="mt-4">
        <h3 className="text-xs font-semibold tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
          Recent activity
        </h3>
        {activity === null ? (
          <div className="mt-2 h-10 animate-pulse rounded-md" style={{ background: 'var(--bg-raised)' }} />
        ) : activity.length === 0 ? (
          <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
            Nothing recorded yet.
          </p>
        ) : (
          <ul className="mt-2 space-y-1.5 text-sm">
            {/* The real audit trail, newest first, capped — the full history is on the case. */}
            {[...activity].reverse().slice(0, 4).map((entry) => (
              <li key={`${entry.at}-${entry.action}`}>
                <span className="font-medium">{entry.actorName}</span>{' '}
                <span style={{ color: 'var(--text-muted)' }}>{entry.note ?? readable(entry.action)}</span>
                <span className="font-num block text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {new Date(entry.at).toLocaleString()}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {error && (
        <p className="mt-3 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      <div className="mt-4 flex gap-2">
        {row.draftLink ? (
          <a
            href={row.draftLink}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex h-9 flex-1 items-center justify-center gap-1.5 text-sm font-medium"
            style={{ border: '1px solid var(--border-default)', borderRadius: 'var(--radius-md)' }}
          >
            <ExternalLink className="h-3.5 w-3.5" aria-hidden />
            View draft
          </a>
        ) : (
          <span
            className="inline-flex h-9 flex-1 items-center justify-center text-sm"
            style={{ color: 'var(--text-muted)' }}
          >
            No link recorded
          </span>
        )}

        {/* Approve and return only exist while the draft is actually with the PM. Rendering them
            on an already-approved draft would offer an action the server answers 409 to. */}
        {reviewable && (
          <>
            <button
              type="button"
              disabled={busy}
              onClick={() => setReturning(true)}
              className="h-9 px-3 text-sm font-medium disabled:opacity-50"
              style={{ border: '1px solid var(--border-default)', borderRadius: 'var(--radius-md)' }}
            >
              Return
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => run(APPROVE)}
              className="h-9 px-3 text-sm font-medium disabled:opacity-50"
              style={{
                background: 'var(--accent-primary)',
                color: '#fff',
                borderRadius: 'var(--radius-md)',
              }}
            >
              {busy ? 'Working…' : 'Approve'}
            </button>
          </>
        )}
      </div>

      <DialogRoot open={returning} onOpenChange={setReturning}>
        <DialogContent
          title={`Return ${row.caseCode}`}
          description="The Case Manager sees your reason and the case stays in Draft / Report."
          footer={
            <button
              type="button"
              disabled={busy || reason.trim() === ''}
              onClick={() => run(RETURN, { reason })}
              className="h-9 px-3 text-sm font-medium disabled:opacity-50"
              style={{
                background: 'var(--accent-primary)',
                color: '#fff',
                borderRadius: 'var(--radius-md)',
              }}
            >
              Return draft
            </button>
          }
        >
          <label className="block text-sm">
            <span className="font-medium">What needs to change?</span>
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={4}
              className="mt-1 w-full p-2 text-sm"
              style={{
                border: '1px solid var(--border-default)',
                borderRadius: 'var(--radius-md)',
                background: 'var(--bg-base)',
              }}
            />
          </label>
        </DialogContent>
      </DialogRoot>
    </aside>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2">
      <dt style={{ color: 'var(--text-muted)' }}>{label}</dt>
      <dd className="truncate text-right">{value}</dd>
    </div>
  )
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
