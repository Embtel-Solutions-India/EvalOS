import { useQuery } from '@tanstack/react-query'
import { stateOf } from '../dashboards/useMetrics'
import { fetchSyncHealth } from './adminApi'
import { SyncSummary } from './adminSummaries'

/**
 * What each outbox intent asked GHL to do, in the words a person would use. UPSERT also carries notes and
 * client-account links, so its label names no entity.
 */
const INTENT_LABELS: Record<string, string> = {
  UPSERT: 'Send update',
  CLOSE: 'Mark won / lost',
  DELETE: 'Delete',
}

/**
 * Whether EvalOS and GHL agree (Unit 68), read-only. A drift is never resolved by pressing a button
 * (D43): each row says whether it will fix itself, and the ones that need a person come first.
 */
export default function SyncHealthPage() {
  const query = useQuery({ queryKey: ['admin', 'sync'], queryFn: ({ signal }) => fetchSyncHealth(signal) })
  const health = query.data
  const drifts = [...(health?.drifts ?? [])].sort(
    (a, b) => Number(b.resolution === 'NEEDS_A_HUMAN') - Number(a.resolution === 'NEEDS_A_HUMAN'),
  )

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Sync health</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Where EvalOS and GHL disagree, and the pushes waiting to reach GHL. The nightly audit finds drift; most of it
          fixes itself on the next sync.
        </p>
      </header>
      {query.isError && !health && <p className="text-sm" style={{ color: 'var(--status-red)' }}>{query.error.message}</p>}
      {!health && !query.isError && <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
      {health && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Tile label="Open drift" value={health.open} hint="Fixes itself on the next sync" />
            <Tile label="Needs a person" value={health.needsAHuman} alarm={health.needsAHuman > 0} hint="Drift no sync can settle" />
            <Tile
              label="Pushes waiting"
              value={health.outbox.pending}
              hint={health.outbox.oldestPending ? `Oldest since ${new Date(health.outbox.oldestPending).toLocaleString()}` : 'Nothing is queued'}
            />
            <Tile label="Pushes failed" value={health.outbox.dead} alarm={health.outbox.dead > 0} hint="Never reached GHL" />
          </div>

          <SyncSummary health={health} state={stateOf(query)} />

          <Table title="Drift" head={['Record', 'Kind', 'Values', 'Resolution']} empty="EvalOS and GHL agree.">
            {drifts.map((d) => (
              <tr key={d.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                <td className="px-4 py-2">{d.entityType.toLowerCase()} {d.field ? `· ${d.field}` : ''}</td>
                <td className="px-4 py-2 text-xs">{d.kind.toLowerCase().replaceAll('_', ' ')}</td>
                <td className="px-4 py-2 text-xs">EvalOS: {d.localValue ?? '—'}<br />GHL: {d.ghlValue ?? '—'}</td>
                <td className="px-4 py-2 text-xs" style={{ color: d.resolution === 'NEEDS_A_HUMAN' ? 'var(--status-red)' : 'var(--text-muted)' }}>
                  {d.resolution === 'NEEDS_A_HUMAN' ? 'Needs a person' : d.resolution === 'GHL_WINS' ? 'GHL’s value will win' : 'EvalOS’s value will win'}
                </td>
              </tr>
            ))}
          </Table>

          <Table title="Failed pushes" head={['Action', 'Record', 'Tries', 'Why it failed', 'Failed at']} empty="No push to GHL has failed recently.">
            {health.outbox.recentlyDead.map((p) => (
              <tr key={p.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                <td className="px-4 py-2 whitespace-nowrap">{INTENT_LABELS[p.intent] ?? p.intent.toLowerCase()}</td>
                <td className="px-4 py-2 font-num text-xs" title={p.entityId} style={{ color: 'var(--text-muted)' }}>{p.entityId.slice(0, 8)}</td>
                <td className="px-4 py-2 font-num text-xs tabular-nums">{p.attempts}</td>
                <td className="px-4 py-2 text-xs">{p.reason ?? p.lastFailure ?? '—'}</td>
                <td className="px-4 py-2 text-xs whitespace-nowrap" style={{ color: 'var(--text-muted)' }}>{new Date(p.deadAt).toLocaleString()}</td>
              </tr>
            ))}
          </Table>
        </>
      )}
    </section>
  )
}

function Tile({ label, value, hint, alarm = false }: { label: string; value: number; hint: string; alarm?: boolean }) {
  return (
    <div
      className="rounded-lg border p-4"
      style={{ borderColor: alarm ? 'var(--status-red)' : 'var(--border-default)', background: 'var(--bg-raised)' }}
    >
      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{label}</p>
      <p className="font-num text-2xl font-semibold tabular-nums" style={alarm ? { color: 'var(--status-red)' } : undefined}>
        {value}
      </p>
      <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>{hint}</p>
    </div>
  )
}

function Table({ title, head, empty, children }: { title: string; head: string[]; empty: string; children: React.ReactNode[] }) {
  return (
    <section className="rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <h2 className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: 'var(--border-default)' }}>
        {title} {children.length > 0 && <span className="font-num font-normal" style={{ color: 'var(--text-muted)' }}>· {children.length}</span>}
      </h2>
      {children.length === 0 ? (
        <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>{empty}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                {head.map((h) => <th key={h} className="px-4 py-2 font-medium">{h}</th>)}
              </tr>
            </thead>
            <tbody>{children}</tbody>
          </table>
        </div>
      )}
    </section>
  )
}
