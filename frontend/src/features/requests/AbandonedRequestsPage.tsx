import { Hourglass } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import { api, unwrap } from '../../lib/api'
import { useMetrics } from '../dashboards/useMetrics'
import { daysSince } from './abandoned'

/** `AbandonedRequestService.Row`. */
type AbandonedRequest = {
  id: string
  brandId: string
  serviceName: string
  clientName: string | null
  email: string | null
  phone: string | null
  startedAt: string
  lastTouchedAt: string
}

function fetchAbandoned(signal?: AbortSignal): Promise<AbandonedRequest[]> {
  return unwrap<AbandonedRequest[]>(api.get('/requests/abandoned', { signal }))
}

/**
 * Requests a client started and never sent (D54): untouched for 48 hours, oldest first. Since the
 * deal opens at submit (D10), nobody else sees these — this list is the chase, done by a person.
 */
export default function AbandonedRequestsPage() {
  const { data, state } = useMetrics<AbandonedRequest[]>((signal) => fetchAbandoned(signal), [])

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Unfinished requests</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Clients who started a request in the portal and have not sent it for two days or more.
        </p>
      </header>

      <Panel title="Oldest first" icon={<Hourglass />}>
        {state.kind === 'error' && (
          <p className="text-sm" style={{ color: 'var(--status-red)' }}>
            {state.note}
          </p>
        )}
        {state.kind === 'loading' && (
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            Loading…
          </p>
        )}
        {data && data.length === 0 && (
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            No unfinished requests. Every request started in the portal has been sent.
          </p>
        )}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="tbl">
              <thead>
                <tr>
                  <th>Client</th>
                  <th>Email</th>
                  <th>Phone</th>
                  <th>Service</th>
                  <th>Started</th>
                  <th className="num">Idle (days)</th>
                </tr>
              </thead>
              <tbody>
                {data.map((row) => (
                  <tr key={row.id}>
                    <td className="font-medium">{row.clientName ?? '—'}</td>
                    <td>
                      {row.email ? (
                        <a href={`mailto:${row.email}`} className="hover:underline">
                          {row.email}
                        </a>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{row.phone ?? '—'}</td>
                    <td>{row.serviceName}</td>
                    <td>{new Date(row.startedAt).toLocaleDateString()}</td>
                    <td className="num">{daysSince(row.lastTouchedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </section>
  )
}
