import { useEffect, useMemo, useRef } from 'react'
import { PopoverContent, PopoverRoot, PopoverTrigger } from '../../components/ui/popover'
import { ROLE_LABELS } from '../../lib/session'
import { STAGE_OWNER, type Stage } from '../board/boardRules'
import type { TimelineEntry } from './caseApi'
import { STAGE_SHORT, formatSpan, progress, type Step } from './caseProgress'

/**
 * The twelve stages left to right under the case name (Unit 66, option A). A summary of the trail,
 * never a replacement for it — Notes & timeline stays the record.
 */
export default function ProgressStrip({
  entries,
  current,
  slaLabel,
}: {
  entries: readonly TimelineEntry[]
  current: Stage
  slaLabel: string | null
}) {
  const steps = useMemo(() => progress(entries, current, new Date()), [entries, current])
  const currentRef = useRef<HTMLLIElement>(null)
  // Below ~900px the strip scrolls; the step that matters is the one in view.
  useEffect(() => {
    currentRef.current?.scrollIntoView({ inline: 'center', block: 'nearest' })
  }, [current])

  return (
    <ol className="mt-3 flex overflow-x-auto pb-1" aria-label="Case progress">
      {steps.map((step, index) => (
        <li
          key={step.stage}
          ref={step.state === 'current' ? currentRef : undefined}
          className="relative min-w-[4.5rem] flex-1 text-center text-[11px]"
        >
          {index > 0 && (
            <span
              aria-hidden
              className="absolute top-[7px] right-1/2 h-0.5 w-full"
              style={{ background: step.state === 'not-reached' ? 'var(--border-default)' : 'var(--accent-primary)' }}
            />
          )}
          <PopoverRoot>
            <PopoverTrigger asChild>
              <button
                type="button"
                aria-label={ariaFor(step)}
                className="relative flex w-full flex-col items-center gap-1"
                style={{
                  color: step.state === 'current' ? 'var(--text-primary)' : 'var(--text-muted)',
                  fontWeight: step.state === 'current' ? 600 : 400,
                }}
              >
                <span className="h-4 w-4 rounded-full border-2" style={dotStyle(step)} />
                <span>
                  {STAGE_SHORT[step.stage]}
                  {step.visits > 1 && (
                    <span
                      className="ml-0.5 rounded px-0.5 text-[10px]"
                      style={{ color: 'var(--status-amber)', background: 'var(--status-amber-bg)' }}
                    >
                      ×{step.visits}
                    </span>
                  )}
                </span>
                <span className="font-num text-[10px] font-normal tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {step.state === 'current' ?
                    `${formatSpan(step.spentMs)} here`
                  : step.firstAt ?
                    new Date(step.firstAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
                  : ''}
                </span>
              </button>
            </PopoverTrigger>
            <PopoverContent label={`${STAGE_SHORT[step.stage]} details`}>
              <StepDetail step={step} slaLabel={step.state === 'current' ? slaLabel : null} />
            </PopoverContent>
          </PopoverRoot>
        </li>
      ))}
    </ol>
  )
}

function dotStyle(step: Step): React.CSSProperties {
  if (step.state === 'current') {
    return { borderColor: 'var(--accent-primary)', background: 'var(--bg-surface)', boxShadow: '0 0 0 4px var(--accent-soft)' }
  }
  if (step.state === 'reached') return { borderColor: 'var(--accent-primary)', background: 'var(--accent-primary)' }
  return { borderColor: 'var(--rail-unknown)', background: 'var(--bg-surface)' }
}

function ariaFor(step: Step): string {
  const state = step.state === 'current' ? 'current' : step.state === 'reached' ? 'reached' : 'not reached'
  return `${STAGE_SHORT[step.stage]}, ${state}${step.visits > 1 ? `, entered ${step.visits} times` : ''}`
}

function StepDetail({ step, slaLabel }: { step: Step; slaLabel: string | null }) {
  const owner = STAGE_OWNER[step.stage]
  if (step.state === 'not-reached') {
    return (
      <p className="text-sm">
        <b>{STAGE_SHORT[step.stage]}</b> — not reached yet.
      </p>
    )
  }
  if (step.visits === 0 && step.state === 'reached') {
    return (
      <p className="text-sm">
        <b>{STAGE_SHORT[step.stage]}</b> — passed; the trail has no date for it.
      </p>
    )
  }
  const muted = { color: 'var(--text-muted)' }
  return (
    <div className="text-sm">
      <p>
        <b>{STAGE_SHORT[step.stage]}</b>
        {owner && <span style={muted}> · {ROLE_LABELS[owner]}</span>}
      </p>
      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        {step.lastAt && (
          <>
            <dt style={muted}>Entered</dt>
            <dd>
              {new Date(step.lastAt).toLocaleString()}
              {step.visits > 1 ? ` (visit ${step.visits})` : ''}
            </dd>
          </>
        )}
        {step.visits > 1 && step.firstAt && (
          <>
            <dt style={muted}>First</dt>
            <dd>{new Date(step.firstAt).toLocaleString()}</dd>
          </>
        )}
        <dt style={muted}>Time here</dt>
        <dd>
          {formatSpan(step.spentMs)}
          {step.visits > 1 ? ' in total' : ''}
        </dd>
        {step.lastNote && (
          <>
            <dt style={muted}>Why</dt>
            <dd>{step.lastNote}</dd>
          </>
        )}
        {slaLabel && (
          <>
            <dt style={muted}>Stage SLA</dt>
            <dd>{slaLabel}</dd>
          </>
        )}
      </dl>
    </div>
  )
}
