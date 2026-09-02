import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchBoard } from '../board/boardApi'
import type { BoardCard, BoardData } from '../board/boardRules'
import { fetchCase, fetchDraftVersions, type CaseDetail, type DraftVersion } from '../case/caseApi'
import { useFilters } from '../shell/filtersContext'
import { myDrafts } from './queueRules'

/**
 * The Case Manager's own drafting queue: what the PM asked for, and what became of each draft.
 *
 * **Two questions the board cannot answer.** `/my-cases` says *which cases are mine*; this says
 * *what did the PM tell me* and *which version are we on*. Both facts live per case, so without
 * this screen a CM chasing a returned draft opens cases one at a time hunting for the comment.
 *
 * **Detail is fetched on expand, not on load.** The strategy notes live on the case payload and the
 * version history on its documents route, so rendering everything eagerly would be two requests per
 * row for rows nobody opened. The list itself costs one call — the same `/api/cases/board` every
 * other queue reads, so this screen's scope cannot drift from the board's.
 */
export default function MyDraftsPage() {
  const { activeBrandId } = useFilters()
  const [data, setData] = useState<BoardData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  const load = useCallback(
    (signal?: AbortSignal) => {
      // No `dueBefore`: this is "what do I owe", not "what is due when", and a date window would
      // silently hide a returned draft with no deadline set.
      fetchBoard(null, activeBrandId, signal)
        .then(setData)
        .catch((cause: Error) => {
          if (!signal?.aborted) setError(cause.message)
        })
    },
    [activeBrandId],
  )

  useEffect(() => {
    const controller = new AbortController()
    load(controller.signal)
    return () => controller.abort()
  }, [load])

  const rows = data ? myDrafts(data) : []

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">My drafts</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {data ? `${rows.length} in progress` : ''}
        </p>
      </header>

      {error && (
        <p className="mt-4 text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {data === null && !error && (
        <div className="mt-4 h-40 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}

      {data && rows.length === 0 && (
        // Operational copy, never "No data" — an empty queue is a statement about the work.
        <p
          className="mt-4 rounded-lg border p-6 text-sm"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-default)',
            color: 'var(--text-muted)',
          }}
        >
          Nothing is with you for drafting.
        </p>
      )}

      {data && rows.length > 0 && (
        <ul className="mt-4 flex flex-col gap-2">
          {rows.map((card) => (
            <Row
              key={card.id}
              card={card}
              expanded={open === card.id}
              onToggle={() => setOpen(open === card.id ? null : card.id)}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

/** The one row state that changes the work: a returned draft is the thing to act on today. */
function returnedFrom(card: BoardCard): boolean {
  return card.pmApprovalStatus === 'RETURNED' || card.clientApprovalStatus === 'REVISION_REQUESTED'
}

function Row({
  card,
  expanded,
  onToggle,
}: {
  card: BoardCard
  expanded: boolean
  onToggle: () => void
}) {
  const [detail, setDetail] = useState<CaseDetail | null>(null)
  const [versions, setVersions] = useState<DraftVersion[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!expanded || detail) return
    const controller = new AbortController()
    Promise.all([fetchCase(card.id, controller.signal), fetchDraftVersions(card.id, controller.signal)])
      .then(([loadedCase, loadedVersions]) => {
        setDetail(loadedCase)
        setVersions(loadedVersions)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => controller.abort()
  }, [expanded, detail, card.id])

  const returned = returnedFrom(card)

  return (
    <li
      className="rounded-lg border"
      style={{
        background: 'var(--bg-surface)',
        // Red only for a draft that has come back — the state that means work today. Colouring
        // every row by stage would make the one that needs action indistinguishable.
        borderColor: returned ? 'var(--status-red)' : 'var(--border-default)',
      }}
    >
      <button type="button" onClick={onToggle} className="flex w-full items-baseline gap-3 p-3 text-left">
        <span className="font-mono text-xs font-medium">{card.caseCode}</span>
        <span className="min-w-0 flex-1 truncate text-sm">{card.clientName ?? 'Unnamed contact'}</span>
        {returned && (
          <span className="text-xs font-semibold" style={{ color: 'var(--status-red)' }}>
            Returned — revise
          </span>
        )}
        <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {readable(card.currentStage)}
        </span>
      </button>

      {expanded && (
        <div
          className="flex flex-col gap-3 border-t p-3"
          style={{ borderColor: 'var(--border-default)' }}
        >
          {failed && (
            <p className="text-sm" style={{ color: 'var(--status-red)' }}>
              Could not load this case.
            </p>
          )}

          {detail && (
            <div>
              <h3 className="text-xs font-semibold tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
                PM strategy notes
              </h3>
              {/*
                Three states, and the middle one is the point — the same shape `StrategyNotes` uses.
                A CM reads without writing, so "not written yet" and "not yours" must not look alike.
              */}
              <p className="mt-1 text-sm whitespace-pre-wrap">
                {!detail.maySeeStrategyNotes ?
                  'Not visible to your role.'
                : (detail.pmStrategyNotes ?? 'The PM has not written any yet.')}
              </p>
            </div>
          )}

          {versions && (
            <div>
              <h3 className="text-xs font-semibold tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
                Draft history
              </h3>
              {versions.length === 0 ?
                <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
                  You have not submitted a version yet.
                </p>
              : <ol className="mt-1 flex flex-col gap-2">
                  {versions.map((version) => (
                    <li key={version.id} className="text-sm">
                      <span className="font-num font-semibold tabular-nums">V{version.version}</span>{' '}
                      <span style={{ color: 'var(--text-muted)' }}>
                        {readable(version.status)} · {new Date(version.uploadedAt).toLocaleDateString()}
                      </span>
                      {/* The PM's comment on THAT version — the reason this screen exists. */}
                      {version.reviewComment && (
                        <p
                          className="mt-0.5 border-l-2 pl-3"
                          style={{ borderColor: 'var(--status-red)' }}
                        >
                          {version.reviewComment}
                        </p>
                      )}
                    </li>
                  ))}
                </ol>
              }
            </div>
          )}

          <Link
            to={`/cases/${card.id}`}
            className="text-sm font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Open the case
          </Link>
        </div>
      )}
    </li>
  )
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
