import { useState } from 'react'
import { AlertTriangle, CalendarClock, ChevronDown, CircleDot, Clock, Hourglass, Pencil, Upload } from 'lucide-react'
import { PopoverContent, PopoverRoot, PopoverTrigger } from '../../components/ui/popover'
import type { Role } from '../../lib/session'
import { actionsFor, type BoardCard, type QuickAction } from '../board/boardRules'
import type { CaseDetail, TimelineEntry } from './caseApi'
import ProgressStrip from './ProgressStrip'
import DeadlineDialog from './DeadlineDialog'
import UploadDraftDialog from './UploadDraftDialog'
import CaseDealDialog from './CaseDealDialog'
import { STAGE_SHORT } from './caseProgress'
import { button, sentence, type Tone } from './caseUi'
import { StatusPill } from './StatusPill'
import { expertEvidenceRequest, maySetDeadline, nextStep, splitActions } from './caseRules'
import { mayUploadDraft } from './draftRules'

/**
 * The case page's header (Unit 66, was `StageActions`): what the case is, who is on it, what to do
 * next, and the twelve-stage progress strip.
 *
 * **Three tiers of action, so the next move is the obvious one.** The first stage transition (or
 * Upload draft, when that is the job) is the solid primary button; any other transition is an
 * outlined secondary; stage-preserving actions sit under More. The next-step band states the same
 * thing in words, coloured by whose move it is.
 *
 * **It sticks below the top bar, offset by `--header-height`, and only from `lg` up.** Two sticky
 * elements at the same `top` hide one another, so the offset is the top bar's own height. Below
 * `lg` the header is tall enough to eat a phone screen, so it scrolls with the page there.
 *
 * **The actions come from `boardRules.actionsFor`, not a second table.** Which transitions are
 * legal for a role at a stage does not depend on the screen, and two copies would be two answers.
 * Illegal actions are not rendered at all, and the server still decides.
 */

const SLA: Record<string, { tone: Tone; label: string }> = {
  ON_TRACK: { tone: 'done', label: 'On track' },
  AT_RISK: { tone: 'pending', label: 'At risk' },
  OVERDUE: { tone: 'blocked', label: 'Overdue' },
}

const EXCEPTION: Record<string, string> = {
  ON_HOLD_AWAITING_CLIENT: 'On hold — waiting on the client',
  EXPERT_DECLINED_REMATCHING: 'The expert declined — the case needs another expert',
  REFUND_REQUESTED: 'Refund requested — waiting on the General Manager’s decision',
}

const FINISHED = new Set(['DELIVERED', 'CLOSED'])

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
  const sla = card.slaStatus ? SLA[card.slaStatus] : null
  const inException = card.exceptionState !== 'NONE'
  const next = nextStep(card.currentStage, card.exceptionState, role)
  const upload = mayUploadDraft(card.currentStage, role)
  const evidence = expertEvidenceRequest(timeline, card.exceptionState)
  const [moreOpen, setMoreOpen] = useState(false)
  const [dealOpen, setDealOpen] = useState(false)
  // The opportunity pop-up: the contact name, or a click on the card that lands on nothing else.
  // Portalled children (menus, dialogs) bubble through React, so only clicks inside this DOM node count.
  const openDealFromCard = (event: React.MouseEvent<HTMLElement>) => {
    const target = event.target as HTMLElement
    if (!event.currentTarget.contains(target) || target.closest('button, a, input, textarea, select, [role="menuitem"]')) return
    setDealOpen(true)
  }
  const finished = FINISHED.has(card.currentStage)

  const due =
    card.deadline ?
      new Date(card.deadline).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
    : 'not set'
  const dueTone: Tone =
    finished || !card.deadline ? 'idle'
    : card.deadlineRisk === 'OVERDUE' ? 'blocked'
    : card.deadlineRisk === 'AT_RISK' ? 'pending'
    : 'idle'

  // Solid means "this is your move": the lead action is primary only when the next step is the
  // reader's own. Upload draft is that move whenever it is offered; otherwise the first transition.
  const yours = next?.startsWith('Your next step') === true
  const leadIsUpload = upload
  const noAction = primary.length === 0 && more.length === 0 && !upload

  return (
    <header
      onClick={detail.maySeeCaseContent ? openDealFromCard : undefined}
      className={`z-10 mb-5 border-b lg:sticky${detail.maySeeCaseContent ? ' cursor-pointer' : ''}`}
      style={{
        top: 'var(--header-height)',
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        marginInline: 'calc(var(--shell-gutter) * -1)',
        paddingInline: 'var(--shell-gutter)',
        paddingBlock: '1rem',
      }}
    >
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 font-mono text-xs" style={{ color: 'var(--text-muted)' }}>
              {card.caseCode}
            </span>
            <StatusPill tone={finished ? 'done' : 'active'}>
              <CircleDot aria-hidden />
              {STAGE_SHORT[card.currentStage]}
            </StatusPill>
            {sla && !inException && (
              <StatusPill tone={sla.tone} title="How long the case has been in this stage, against its target">
                <Clock aria-hidden />
                Stage SLA {sla.label.toLowerCase()}
              </StatusPill>
            )}
            {inException && (
              <StatusPill tone="blocked">
                <AlertTriangle aria-hidden />
                {sentence(card.exceptionState)}
              </StatusPill>
            )}
            {!detail.summary.paid && !finished && (
              <StatusPill tone="pending" title="Documents cannot be marked complete until the case is paid">
                Unpaid
              </StatusPill>
            )}
            {maySetDeadline(role) ?
              <DeadlineDialog
                caseId={card.id}
                deadline={card.deadline}
                onSaved={onChanged}
                trigger={
                  <button
                    type="button"
                    aria-label={`Due ${due}. Change the deadline`}
                    title="Change the deadline"
                    className="rounded-md focus-visible:shadow-[var(--ring-focus)] focus-visible:outline-none"
                  >
                    <StatusPill tone={dueTone}>
                      <CalendarClock aria-hidden />
                      <span className="font-num tabular-nums">Due {due}</span>
                      <Pencil aria-hidden />
                    </StatusPill>
                  </button>
                }
              />
            : <StatusPill tone={dueTone}>
                <CalendarClock aria-hidden />
                <span className="font-num tabular-nums">Due {due}</span>
              </StatusPill>
            }
          </div>

          {/* Withheld and unnamed are different facts and must not share a label: the supply-side
              role may not see the client at all, which is not the same as a case with no contact
              linked to it. `maySeeCaseContent` is the server's own answer to which one this is. */}
          <h1 className="mt-2 text-xl font-semibold tracking-tight text-balance">
            {detail.maySeeCaseContent ?
              <button type="button" onClick={() => setDealOpen(true)} className="text-left hover:underline" title="Open opportunity details">
                {detail.clientName ?? 'Unnamed contact'}
              </button>
            : <span style={{ color: 'var(--text-muted)' }}>Client withheld</span>}
          </h1>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            {sentence(card.serviceType)}
          </p>
          {/* Unit 67: who is on the case, so "who do I ask?" is not a trip to the timeline. */}
          <dl className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
            <TeamMember role="PM" name={detail.team.pm} />
            <TeamMember role="Case manager" name={detail.team.cm} />
            <TeamMember role="Coordinator" name={detail.team.coordinator} />
          </dl>
        </div>

        <div className="flex flex-wrap gap-2 lg:max-w-[28rem] lg:justify-end">
          {upload && (
            <UploadDraftDialog
              caseId={card.id}
              nextVersion={detail.summary.draftVersionCount + 1}
              onUploaded={onChanged}
              trigger={
                <button type="button" disabled={busy} className={yours ? button.primary : button.secondary}>
                  <Upload aria-hidden />
                  Upload draft
                </button>
              }
            />
          )}
          {primary.map((action, index) => (
            <button
              key={action.path}
              type="button"
              disabled={busy}
              onClick={() => onAction(action)}
              className={index === 0 && !leadIsUpload && yours ? button.primary : button.secondary}
            >
              {action.label}
            </button>
          ))}
          {more.length > 0 && (
            <PopoverRoot open={moreOpen} onOpenChange={setMoreOpen}>
              <PopoverTrigger asChild>
                <button type="button" disabled={busy} className={button.secondary} aria-label="More actions">
                  More
                  <ChevronDown aria-hidden />
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
                        className="w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-[var(--bg-raised)] focus-visible:bg-[var(--bg-raised)] focus-visible:outline-none"
                      >
                        {action.label}
                      </button>
                    </li>
                  ))}
                </ul>
              </PopoverContent>
            </PopoverRoot>
          )}
        </div>
      </div>

      <NextStepBand
        exception={inException ? (EXCEPTION[card.exceptionState] ?? sentence(card.exceptionState)) : null}
        next={next}
        noAction={noAction}
        finished={finished}
      />

      <ProgressStrip entries={timeline} current={card.currentStage} slaLabel={sla?.label ?? null} />

      {/* Everyone on the case sees why signing stopped, not only the Coordinators who were told. */}
      {evidence !== null && (
        <div
          role="status"
          className="mt-3 rounded-lg border px-3 py-2 text-sm"
          style={{ background: 'var(--status-amber-bg)', borderColor: 'var(--status-amber)' }}
        >
          <p className="font-semibold" style={{ color: 'var(--status-amber)' }}>
            The expert asked for more evidence before signing
          </p>
          {evidence && <p className="mt-0.5 whitespace-pre-wrap">{evidence}</p>}
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            It is on this case's document checklist. The Coordinator asks the client from Doc checklists and
            presses Resume once it is in; the expert can sign again after that.
          </p>
        </div>
      )}

      {/* A refused transition explains itself here rather than vanishing. */}
      {error && (
        <p role="alert" className="mt-3 flex items-start gap-1.5 text-sm" style={{ color: 'var(--status-red)' }}>
          <AlertTriangle aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
          {error}
        </p>
      )}
      {detail.maySeeCaseContent && <CaseDealDialog caseId={card.id} open={dealOpen} onOpenChange={setDealOpen} />}
    </header>
  )
}

function TeamMember({ role, name }: { role: string; name: string | null }) {
  return (
    <div className="flex gap-1.5">
      <dt style={{ color: 'var(--text-muted)' }}>{role}</dt>
      <dd className="font-medium" style={{ color: name ? 'var(--text-primary)' : 'var(--text-muted)' }}>
        {name ?? 'Not assigned'}
      </dd>
    </div>
  )
}

/**
 * The sentence that answers "what happens next?", coloured by whose move it is: accent when it is
 * the reader's, red when the case is stopped, neutral when it waits on someone else.
 */
function NextStepBand({
  exception,
  next,
  noAction,
  finished,
}: {
  exception: string | null
  next: string | null
  noAction: boolean
  finished: boolean
}) {
  const text = exception ?? next ?? (finished ? 'Nothing left to do on this case.' : null)
  if (!text) return null
  const yours = !exception && next?.startsWith('Your next step') === true
  const style =
    exception ? { background: 'var(--status-red-bg)', color: 'var(--status-red)' }
    : yours ? { background: 'var(--accent-soft)', color: 'var(--accent-primary)' }
    : { background: 'var(--bg-raised)', color: 'var(--text-muted)' }
  const Icon = exception ? AlertTriangle : yours ? CircleDot : Hourglass
  return (
    <p role="status" className="mt-4 flex items-start gap-2 rounded-md px-3 py-2 text-sm font-medium" style={style}>
      <Icon aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
      <span>
        {text}
        {noAction && !finished && !exception && !yours && (
          <span className="font-normal"> No action on this page is yours right now.</span>
        )}
      </span>
    </p>
  )
}
