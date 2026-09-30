import { useState } from 'react'
import { PopoverContent, PopoverRoot, PopoverTrigger } from '../../components/ui/popover'
import type { Role } from '../../lib/session'
import { actionsFor, type BoardCard, type QuickAction } from '../board/boardRules'
import type { CaseDetail, TimelineEntry } from './caseApi'
import ProgressStrip from './ProgressStrip'
import DeadlineDialog from './DeadlineDialog'
import UploadDraftDialog from './UploadDraftDialog'
import { maySetDeadline, nextStep, splitActions } from './caseRules'
import { mayUploadDraft } from './draftRules'

/**
 * The case page's sticky header (Unit 66, was `StageActions`): what the case is, the due date,
 * one action bar, the next step and the twelve-stage progress strip.
 *
 * **It sticks below the top bar, offset by `--header-height`.** Two sticky elements at the same
 * `top` is not a layering problem to solve with z-index — whichever loses is simply hidden. The
 * shell's header is the one pinned to the viewport, so everything that sticks under it takes its
 * height as an offset.
 *
 * **The actions come from `boardRules.actionsFor`, not a second table.** Which transitions
 * are legal for a role at a stage does not depend on which screen you are looking at, and
 * two copies would be two answers — the board offering something the detail page hides is a
 * bug nobody would spot until it was in front of a user. Illegal actions are not rendered at
 * all (spec acceptance criterion 2), and the server still decides. The header only splits that
 * answer (`splitActions`): stage transitions and exception exits as buttons, the rest under More.
 */

const SLA_TONE: Record<string, { fg: string; bg: string; label: string }> = {
  ON_TRACK: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'On track' },
  AT_RISK: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'At risk' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'Overdue' },
}

function readable(value: string | null | undefined): string {
  return value ? value.replaceAll('_', ' ').toLowerCase() : '—'
}

export default function CaseHeader({
  detail,
  timeline,
  role,
  busy,
  error,
  onAction,
  onChanged,
}: {
  detail: CaseDetail
  timeline: readonly TimelineEntry[]
  role: Role
  busy: boolean
  error: string | null
  onAction: (action: QuickAction) => void
  onChanged: () => void
}) {
  const card = detail.summary as BoardCard
  const { primary, more } = splitActions(actionsFor(card, role))
  const sla = card.slaStatus ? SLA_TONE[card.slaStatus] : null
  const inException = card.exceptionState !== 'NONE'
  const next = nextStep(card.currentStage, card.exceptionState, role)
  const upload = mayUploadDraft(card.currentStage, role)
  const [moreOpen, setMoreOpen] = useState(false)
  const due =
    card.deadline ?
      new Date(card.deadline).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
    : 'not set'
  const button = 'rounded-md px-2.5 py-1.5 text-sm font-medium disabled:opacity-40'
  const chip = 'rounded-md px-1.5 py-0.5 text-xs font-semibold'

  return (
    <header
      // **Sticks BELOW the top bar, not at the viewport top.** `sticky top-0` put this header
      // and `TopBar` on the same 0 offset, and the top bar wins on z-index (z-20 over z-10) —
      // so the case code and its SLA badge were painted over. The offset is the top bar's own
      // `--header-height`, so the two cannot drift apart.
      //
      // **`-mt-6` is gone, and it was the half that broke this even unscrolled.** It existed to
      // cancel the content area's top padding so the band could bleed to the edge — but
      // `AppShell`'s main is `padding: 0 gutter gutter`, with NO top padding. So it cancelled
      // nothing and simply pulled the header 24px up into the top bar's space, clipping it on a
      // page that had not been scrolled at all.
      //
      // The horizontal bleed now uses `--shell-gutter` instead of `-mx-6`/`px-6`: the gutter is
      // 1.25rem and `-mx-6` is 1.5rem, so the band overhung the content column by 4px a side.
      className="sticky z-10 mb-4 border-b"
      style={{
        top: 'var(--header-height)',
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        marginInline: 'calc(var(--shell-gutter) * -1)',
        paddingInline: 'var(--shell-gutter)',
        paddingBlock: '1rem',
      }}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-xs" style={{ color: 'var(--text-muted)' }}>
              {card.caseCode}
            </span>
            {sla && (
              <span className={chip} style={{ color: sla.fg, background: sla.bg }}>
                Stage SLA: {sla.label.toLowerCase()}
              </span>
            )}
            {inException && (
              <span className={chip} style={{ color: 'var(--status-red)', background: 'var(--status-red-bg)' }}>
                {readable(card.exceptionState)}
              </span>
            )}
            {maySetDeadline(role) ?
              <DeadlineDialog
                caseId={card.id}
                deadline={card.deadline}
                onSaved={onChanged}
                trigger={
                  <button
                    type="button"
                    aria-label={`Due ${due}, change deadline`}
                    className={`font-num tabular-nums ${chip}`}
                    style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}
                  >
                    Due {due} ✎
                  </button>
                }
              />
            : <span className={`font-num tabular-nums ${chip}`} style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}>
                Due {due}
              </span>
            }
          </div>

          {/* Withheld and unnamed are different facts and must not share a label: the supply-side
              role may not see the client at all, which is not the same as a case with no contact
              linked to it. `maySeeCaseContent` is the server's own answer to which one this is. */}
          <h1 className="mt-1 text-lg font-semibold tracking-tight">
            {detail.maySeeCaseContent ?
              (detail.clientName ?? 'Unnamed contact')
            : <span style={{ color: 'var(--text-muted)' }}>Client withheld</span>}
          </h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            {readable(card.serviceType)}
          </p>
        </div>

        <div className="flex flex-col items-end gap-1.5">
          <div className="flex flex-wrap justify-end gap-1.5">
            {upload && (
              <UploadDraftDialog
                caseId={card.id}
                nextVersion={detail.summary.draftVersionCount + 1}
                onUploaded={onChanged}
                trigger={
                  <button
                    type="button"
                    disabled={busy}
                    className={button}
                    style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
                  >
                    Upload draft
                  </button>
                }
              />
            )}
            {primary.map((action) => (
              <button
                key={action.path}
                type="button"
                disabled={busy}
                onClick={() => onAction(action)}
                className={button}
                style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}
              >
                {action.label}
              </button>
            ))}
            {more.length > 0 && (
              <PopoverRoot open={moreOpen} onOpenChange={setMoreOpen}>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    disabled={busy}
                    className={button}
                    style={{ background: 'var(--bg-raised)', color: 'var(--text-primary)' }}
                  >
                    More ▾
                  </button>
                </PopoverTrigger>
                <PopoverContent label="More actions">
                  <ul className="flex flex-col">
                    {more.map((action) => (
                      <li key={action.path}>
                        <button
                          type="button"
                          onClick={() => {
                            setMoreOpen(false)
                            onAction(action)
                          }}
                          className="w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-[var(--bg-raised)]"
                        >
                          {action.label}
                        </button>
                      </li>
                    ))}
                  </ul>
                </PopoverContent>
              </PopoverRoot>
            )}
            {primary.length === 0 && more.length === 0 && !upload && (
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                No action is yours at this stage.
              </span>
            )}
          </div>
          {next && (
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              {next}
            </p>
          )}
        </div>
      </div>

      <ProgressStrip entries={timeline} current={card.currentStage} slaLabel={sla?.label ?? null} />

      {/* A refused transition explains itself here rather than vanishing. */}
      {error && (
        <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </header>
  )
}
