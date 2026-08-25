import type { ReactNode } from 'react'
import { useMe } from '../../lib/authContext'
import type { Role } from '../../lib/session'
import CaseManagerDashboard from './CaseManagerDashboard'
import CoordinatorDashboard from './CoordinatorDashboard'
import ExpertNetworkDashboard from './ExpertNetworkDashboard'
import PmDashboard from './PmDashboard'
import RevenueDashboard from './RevenueDashboard'

/**
 * One dashboard per role, chosen from a table rather than a chain of branches — the same shape
 * `NAV_ITEMS` and `STAGE_ACCESS` use, so a role's landing screen is a data row.
 *
 * **Every role now reads live figures.** The placeholder tiles this file used to render are gone:
 * they existed so each role's landing page could say what would be there, and Unit 22's five
 * slices replaced them role by role.
 *
 * The GM and Brand Manager share `RevenueDashboard`, and the difference between them is data
 * rather than layout — the GM's payload carries a per-brand breakdown and narrows with the brand
 * switcher, the Brand Manager's is their brand and the switcher is locked. **They are not the
 * same role with a filter, though**: `SEES_STRATEGY_NOTES` excludes the Brand Manager, so nothing
 * on a shared screen may assume oversight means full visibility.
 */
const DASHBOARDS: Record<Role, ReactNode> = {
  GM: <RevenueDashboard />,
  BRAND_MANAGER: <RevenueDashboard />,
  PROJECT_MANAGER: <PmDashboard />,
  PROJECT_COORDINATOR: <CoordinatorDashboard />,
  CASE_MANAGER: <CaseManagerDashboard />,
  EXPERT_NETWORK_MANAGER: <ExpertNetworkDashboard />,
}

export default function RoleDashboard() {
  return DASHBOARDS[useMe().role]
}
