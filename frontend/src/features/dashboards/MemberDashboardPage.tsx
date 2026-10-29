import { useState, type ReactNode, useEffect } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import type { Role } from '../../lib/session'
import { setViewAs } from '../../lib/viewAs'
import PmDashboard from './PmDashboard'
import CoordinatorDashboard from './CoordinatorDashboard'
import CaseManagerDashboard from './CaseManagerDashboard'
import ExpertNetworkDashboard from './ExpertNetworkDashboard'

/** The dashboards the GM may open as one member — the roles `JwtFilter.VIEWABLE` admits. */
const BY_ROLE: Partial<Record<Role, ReactNode>> = {
  PROJECT_MANAGER: <PmDashboard />,
  PROJECT_COORDINATOR: <CoordinatorDashboard />,
  CASE_MANAGER: <CaseManagerDashboard />,
  EXPERT_NETWORK_MANAGER: <ExpertNetworkDashboard />,
}

/**
 * The GM opens one production member's own dashboard from the Team card (2026-10-30). The server runs those
 * reads as that member, so the figures are exactly theirs; this page only says whose they are.
 */
export default function MemberDashboardPage() {
  const { memberId = '' } = useParams<{ memberId: string }>()
  const [params] = useSearchParams()
  const role = params.get('role') as Role | null
  const name = params.get('name') ?? 'This member'
  const dashboard = role ? BY_ROLE[role] : undefined

  // Set before the dashboard below renders (its reads fire in effects after this), cleared on leaving.
  useState(() => setViewAs(memberId))
  useEffect(() => {
    setViewAs(memberId)
    return () => setViewAs(null)
  }, [memberId])

  return (
    <section>
      <div
        role="status"
        className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-lg border px-3 py-2 text-sm"
        style={{ background: 'var(--accent-soft)', borderColor: 'var(--accent-primary)' }}
      >
        <span>
          Viewing <strong>{name}</strong>&apos;s dashboard, exactly as they see it.
        </span>
        <Link to="/dashboard" className="font-medium hover:underline" style={{ color: 'var(--accent-primary)' }}>
          Back to your dashboard
        </Link>
      </div>
      {dashboard ?? <p className="text-sm">There is no dashboard to show for this member.</p>}
    </section>
  )
}
