import { useQuery } from '@tanstack/react-query'
import { fetchSyncHealth } from './adminApi'

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
          <div className="grid gap-3 sm:grid-cols-4">
            <Tile label="Open drift" value={health.open} />
            <Tile label="Needs a person" value={health.needsAHuman} alarm={health.needsAHuman > 0} />
            <Tile label="Pushes waiting" value={health.outbox.pending} />
            <Tile label="Pushes failed" value={health.outbox.dead} alarm={health.outbox.dead > 0} />
          </div>

          <Table title="Drift" empty="EvalOS and GHL agree.">
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

          <Table title="Failed pushes" empty="No push to GHL has failed recently.">
            {health.outbox.recentlyDead.map((p) => (
              <tr key={p.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                <td className="px-4 py-2 text-xs">{p.intent.toLowerCase()}</td>
                <td className="px-4 py-2 text-xs">{p.attempts} attempts</td>
                <td className="px-4 py-2 text-xs">{p.reason ?? p.lastFailure ?? '—'}</td>
                <td className="px-4 py-2 text-xs">{new Date(p.deadAt).toLocaleString()}</td>
              </tr>
            ))}
          </Table>
        </>
      )}
    </section>
  )
}

function Tile({ label, value, alarm = false }: { label: string; value: number; alarm?: boolean }) {
  return (
    <div className="rounded-lg border p-4" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{label}</p>
      <p className="font-num text-2xl font-semibold tabular-nums" style={alarm ? { color: 'var(--status-red)' } : undefined}>
        {value}
      </p>
    </div>
  )
}

function Table({ title, empty, children }: { title: string; empty: string; children: React.ReactNode[] }) {
  return (
    <section className="rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <h2 className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: 'var(--border-default)' }}>{title}</h2>
      {children.length === 0 ? (
        <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>{empty}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm"><tbody>{children}</tbody></table>
        </div>
      )}
    </section>
  )
}
