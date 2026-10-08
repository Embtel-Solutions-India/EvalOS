import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { STAGE_SHORT } from '../case/caseProgress'
import type { StageCount } from './pmMetricsApi'
import { ageText, barPercent, stageHref } from './pmOverviewRules'

/** One bar per pipeline stage: how many cases are there, and how long the typical one has waited. */
export function StageFunnel({ stages, state, className }: { stages?: StageCount[]; state: CardState; className?: string }) {
  const max = Math.max(0, ...(stages ?? []).map((row) => row.count))
  return (
    <Card
      title="Pipeline by stage"
      note="Cases per stage right now (Delivered: in the period). Age is the median business hours in the stage."
      state={state}
      className={className}
    >
      <ul className="space-y-1">
        {stages?.map((row) => {
          const href = stageHref(row.stage)
          const body = (
            <>
              <span className="w-28 shrink-0 truncate text-sm">{STAGE_SHORT[row.stage]}</span>
              <span className="h-3 flex-1 overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
                <span
                  className="block h-full rounded-md"
                  style={{ width: `${barPercent(row.count, max)}%`, background: 'var(--accent-primary)' }}
                />
              </span>
              <span className="font-num w-10 text-right text-sm tabular-nums">{row.count}</span>
              <span className="font-num w-14 text-right text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {row.stage === 'DELIVERED' ? '' : ageText(row.medianAgeBusinessHours)}
              </span>
            </>
          )
          return (
            <li key={row.stage}>
              {href ? (
                <Link to={href} className="flex items-center gap-3 py-1">{body}</Link>
              ) : (
                <div className="flex items-center gap-3 py-1">{body}</div>
              )}
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
