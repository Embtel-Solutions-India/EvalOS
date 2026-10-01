import { useState } from 'react'
import { useMe } from '../../lib/authContext'
import { performAction } from '../board/boardApi'
import { actionsFor, prefill, type BoardCard, type QuickAction } from '../board/boardRules'
import QuickActionDialog from '../board/QuickActionDialog'

/**
 * Act on a case from a queue row (Unit 67): whatever the case header offers this role on this case,
 * from the same `actionsFor`, so the two can never disagree.
 *
 * A native `<select>`: keyboard and screen-reader behaviour for free, and it takes one line of the
 * row. Nothing reloads by hand — the `api` interceptor re-reads every case-shaped query after the
 * write (Unit 70a), so the row moves to its new lane by itself.
 */
export default function RowActions({ card }: { card: BoardCard }) {
  const me = useMe()
  const [pending, setPending] = useState<QuickAction | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const actions = actionsFor(card, me.role)

  if (actions.length === 0) return null

  const run = async (action: QuickAction, values: Record<string, string>) => {
    setPending(null)
    setRefusal(null)
    try {
      await performAction(card.id, action, values)
    } catch (cause: unknown) {
      // The server's refusals are sentence fragments; name the action and the case around them.
      setRefusal(`${action.label} was refused — ${cause instanceof Error ? cause.message : 'the server said no'}.`)
    }
  }

  return (
    // A row that opens on click must not open when somebody picks an action.
    <span className="inline-flex flex-col items-end gap-1" onClick={(e) => e.stopPropagation()}>
      <select
        aria-label={`Act on ${card.caseCode}`}
        value=""
        onChange={(e) => setPending(actions.find((a) => a.path === e.target.value) ?? null)}
        className="rounded-md border px-2 py-1 text-xs"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
      >
        <option value="">Act…</option>
        {actions.map((action) => (
          <option key={action.path} value={action.path}>
            {action.label}
          </option>
        ))}
      </select>
      {refusal && (
        <span className="max-w-64 text-right text-xs" style={{ color: 'var(--status-red)' }}>
          {refusal}
        </span>
      )}
      {pending && (
        <QuickActionDialog
          action={pending}
          caseId={card.id}
          caseCode={card.caseCode}
          initial={prefill(card, pending)}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending, values)}
        />
      )}
    </span>
  )
}
