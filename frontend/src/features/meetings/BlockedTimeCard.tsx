import { useMemo, useState } from 'react'
import { Card } from '../../components/ui/card'
import { useMetrics } from '../dashboards/useMetrics'
import {
  blockTime,
  fetchBlockedTime,
  unblockTime,
  type BlockedTime,
} from '../opportunities/opportunityApi'

/**
 * The caller's own blocked-off time, in GHL (Unit 60).
 *
 * <p>A block is GHL's: it keeps the caller's time out of every calendar's free slots. It is keyed
 * by the caller's GHL user, linked by email on the reference sweep. An unlinked account gets the
 * server's reason as the card's error, not an empty list that reads as "no blocks".
 */
export function BlockedTimeCard() {
  const window30 = useMemo(() => {
    const from = new Date()
    return { from, to: new Date(from.getTime() + 30 * 24 * 60 * 60 * 1000) }
  }, [])
  const { data, state, reload } = useMetrics<readonly BlockedTime[]>(
    (signal) => fetchBlockedTime(window30.from, window30.to, signal),
    [window30],
  )

  const [title, setTitle] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ready = start !== '' && end !== '' && new Date(end) > new Date(start)

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      reload()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'GHL refused that.')
    } finally {
      setBusy(false)
    }
  }

  function add(event: React.FormEvent) {
    event.preventDefault()
    if (!ready) return
    void run(async () => {
      // datetime-local is wall-clock time in the browser's zone; toISOString makes it an instant.
      await blockTime({
        title: title.trim() || undefined,
        startTime: new Date(start).toISOString(),
        endTime: new Date(end).toISOString(),
      })
      setTitle('')
      setStart('')
      setEnd('')
    })
  }

  const blocks = data ?? []
  return (
    <Card
      title="Blocked time"
      state={
        state.kind === 'ok' && blocks.length === 0
          ? { kind: 'empty', note: 'Nothing blocked in the next 30 days.' }
          : state
      }
    >
      <ul className="divide-y" style={{ borderColor: 'var(--border-subtle)' }}>
        {blocks.map((block) => (
          <li key={block.id} className="flex flex-wrap items-baseline gap-3 py-1.5 text-sm">
            <span className="font-num tabular-nums">{span(block.startTime, block.endTime)}</span>
            <span className="min-w-0 flex-1 truncate">{block.title || 'Blocked'}</span>
            <button
              type="button"
              onClick={() => void run(() => unblockTime(block.id))}
              disabled={busy}
              className="text-xs disabled:opacity-50"
              style={{ color: 'var(--status-red)' }}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>
      <form onSubmit={add} className="mt-3 grid gap-2 sm:grid-cols-[1fr_auto_auto_auto] sm:items-end">
        <label className="grid gap-1 text-xs">
          Reason (optional)
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="rounded-lg border px-2 py-1 text-sm"
            style={{ borderColor: 'var(--border-subtle)' }}
          />
        </label>
        <label className="grid gap-1 text-xs">
          From
          <input
            type="datetime-local"
            value={start}
            onChange={(e) => setStart(e.target.value)}
            className="rounded-lg border px-2 py-1 text-sm"
            style={{ borderColor: 'var(--border-subtle)' }}
          />
        </label>
        <label className="grid gap-1 text-xs">
          To
          <input
            type="datetime-local"
            value={end}
            min={start || undefined}
            onChange={(e) => setEnd(e.target.value)}
            className="rounded-lg border px-2 py-1 text-sm"
            style={{ borderColor: 'var(--border-subtle)' }}
          />
        </label>
        <button
          type="submit"
          disabled={!ready || busy}
          className="rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
          style={{ background: 'var(--accent-primary)', color: '#fff' }}
        >
          Block time
        </button>
      </form>
      {error && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }} role="alert">
          {error}
        </p>
      )}
    </Card>
  )
}

/** GHL's strings, shown in the browser's zone when they parse and verbatim when they do not. */
function span(start: string, end: string): string {
  const a = new Date(start)
  const b = new Date(end)
  if (Number.isNaN(a.getTime()) || Number.isNaN(b.getTime())) return `${start} – ${end}`
  const day = (d: Date) =>
    d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
  const t = (d: Date) => d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  return a.toDateString() === b.toDateString()
    ? `${day(a)} ${t(a)}–${t(b)}`
    : `${day(a)} ${t(a)} – ${day(b)} ${t(b)}`
}
