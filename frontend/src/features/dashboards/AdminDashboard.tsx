import { KpiCard } from '../../components/ui/card'
import { fetchPipelines, fetchStaff, fetchSyncHealth, type MirroredPipeline, type StaffMember, type SyncHealth } from '../admin/adminApi'
import { useMetrics } from './useMetrics'

/**
 * The Administrator's landing screen (spec 78): what needs an administrator, each figure linking to the
 * screen that fixes it. Deliberately not the GM's overview — that one reads revenue, which is gated to
 * whoever may see a deal value, and the Admin may not. Everything here comes from the admin endpoints
 * the role already owns, so it needs no new server surface.
 */
export default function AdminDashboard() {
  const staff = useMetrics<StaffMember[]>((signal) => fetchStaff(signal), [], { refreshEvery: 60_000 })
  const pipelines = useMetrics<MirroredPipeline[]>((signal) => fetchPipelines(signal), [], { refreshEvery: 60_000 })
  const sync = useMetrics<SyncHealth>((signal) => fetchSyncHealth(signal), [], { refreshEvery: 60_000 })

  const active = staff.data?.filter((member) => member.active).length ?? 0
  const live = pipelines.data?.filter((pipeline) => pipeline.missingSince === null) ?? []
  const untagged = live.filter((pipeline) => pipeline.purpose === 'UNASSIGNED').length

  return (
    <section>
      <h1 className="text-2xl font-semibold tracking-tight">Administration</h1>
      <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
        Staff, pipelines and the GHL connection. Business dashboards are read-only from here.
      </p>

      <div className="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Active staff"
          state={staff.state}
          value={active}
          denominator={staff.data ? `of ${staff.data.length} accounts` : undefined}
          to="/admin/staff"
        />
        <KpiCard
          title="Pipelines with no purpose"
          state={pipelines.state}
          value={untagged}
          denominator={pipelines.data ? `of ${live.length} live pipelines` : undefined}
          tone={untagged > 0 ? 'warn' : undefined}
          to="/admin/pipelines"
        />
        <KpiCard
          title="Open drift with GHL"
          state={sync.state}
          value={sync.data?.open ?? 0}
          denominator={sync.data ? `${sync.data.needsAHuman} need a person` : undefined}
          tone={sync.data && sync.data.needsAHuman > 0 ? 'bad' : undefined}
          to="/admin/sync"
        />
        <KpiCard
          title="Dead outbox items"
          state={sync.state}
          value={sync.data?.outbox.dead ?? 0}
          denominator={sync.data ? `${sync.data.outbox.pending} waiting to send` : undefined}
          tone={sync.data && sync.data.outbox.dead > 0 ? 'bad' : undefined}
          to="/admin/sync"
        />
      </div>
    </section>
  )
}
