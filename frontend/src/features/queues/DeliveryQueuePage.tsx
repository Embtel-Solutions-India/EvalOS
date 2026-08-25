import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchBoard, performAction } from '../board/boardApi'
import { QUICK_ACTIONS, type BoardCard, type BoardData } from '../board/boardRules'
import { useFilters } from '../shell/filtersContext'
import { deliveryQueue, riskColor, riskLabel } from './queueRules'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'

const DELIVER = QUICK_ACTIONS.find((action) => action.path === 'deliver')!

/**
 * The Coordinator's delivery queue — closes **G3**.
 *
 * The transitions behind it (`deliver`, `close`) have existed since Unit 04 and are already
 * Coordinator-gated, so this is genuinely only a screen: no new endpoint, no new permission.
 *
 * **Delivery confirms in a dialog**, unlike the inline actions elsewhere. It is the one action
 * here that reaches a client and cannot be taken back — the letter has left. The confirmation
 * names the client so a misclick on the wrong row is caught before it is a delivered document.
 */
export default function DeliveryQueuePage() {
  const { activeBrandId } = useFilters()
  const [data, setData] = useState<BoardData | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    const controller = new AbortController()
    setError(null)
    fetchBoard(null, activeBrandId, controller.signal)
      .then(setData)
      .catch((cause: Error) => {
        if (!controller.signal.aborted) setError(cause.message)
      })
    return () => controller.abort()
  }

  useEffect(load, [activeBrandId])

  const rows = data ? deliveryQueue(data) : []

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Delivery queue</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {data ? `${rows.length} ready` : ''}
        </p>
      </header>

      {error && (
        <p className="mt-4 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {data === null && !error && (
        <div className="mt-4 h-32 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}

      {data && rows.length === 0 && (
        <p
          className="mt-4 rounded-lg border p-6 text-sm"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-default)',
            color: 'var(--text-muted)',
          }}
        >
          Nothing is waiting to be delivered.
        </p>
      )}

      <ul className="mt-4 space-y-2">
        {rows.map((card) => (
          <DeliveryRow key={card.id} card={card} onDelivered={load} />
        ))}
      </ul>
    </section>
  )
}

function DeliveryRow({ card, onDelivered }: { card: BoardCard; onDelivered: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function deliver() {
    setBusy(true)
    setError(null)
    try {
      await performAction(card.id, DELIVER, {})
      // Say what happened before the list refreshes under them. A row that simply disappears
      // leaves the reader unsure whether it went out or the filter moved.
      setDone(true)
      onDelivered()
    } catch (cause) {
      setError((cause as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <li
      className="flex flex-wrap items-center gap-3 rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="min-w-48 flex-1">
        <Link to={`/cases/${card.id}`} className="font-mono text-xs font-medium">
          {card.caseCode}
        </Link>
        <p className="text-sm font-medium">{card.clientName ?? 'Unnamed contact'}</p>
        <p className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          <span
            className="mr-1.5 inline-block h-2 w-2 rounded-full align-middle"
            style={{ background: riskColor(card.deadlineRisk) }}
            aria-hidden
          />
          <span className="sr-only">{riskLabel(card.deadlineRisk)}</span>
          due {card.deadline ? new Date(card.deadline).toLocaleDateString() : 'not set'}
        </p>
      </div>

      {error && (
        <p className="w-full text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {done ? (
        <span className="text-sm font-medium" style={{ color: 'var(--status-green)' }}>
          Delivered
        </span>
      ) : (
        <DialogRoot>
          <DialogTrigger asChild>
            <button
              type="button"
              disabled={busy}
              className="h-9 px-3 text-sm font-medium disabled:opacity-50"
              style={{
                background: 'var(--accent-primary)',
                color: '#fff',
                borderRadius: 'var(--radius-md)',
              }}
            >
              {busy ? 'Delivering…' : 'Deliver'}
            </button>
          </DialogTrigger>
          <DialogContent
            title="Deliver the signed letter"
            description={`This sends ${card.clientName ?? 'the client'} their final letter. It cannot be undone.`}
            footer={
              <button
                type="button"
                disabled={busy}
                onClick={deliver}
                className="h-9 px-3 text-sm font-medium disabled:opacity-50"
                style={{
                  background: 'var(--accent-primary)',
                  color: '#fff',
                  borderRadius: 'var(--radius-md)',
                }}
              >
                Deliver now
              </button>
            }
          >
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              The case moves to delivered and the timeline records who sent it. Closing the case is
              a separate step, so there is still a chance to handle anything the client raises.
            </p>
          </DialogContent>
        </DialogRoot>
      )}
    </li>
  )
}
