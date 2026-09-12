import type { ReactNode } from 'react'
import { useMe } from '../../lib/authContext'
import type { Role } from '../../lib/session'
import CaseManagerDashboard from './CaseManagerDashboard'
import CoordinatorDashboard from './CoordinatorDashboard'
import ExpertNetworkDashboard from './ExpertNetworkDashboard'
import OpportunityBoardPage from '../opportunities/OpportunityBoardPage'
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
  // **Their board is their dashboard, and that is a decision rather than a gap.** Every other
  // role lands on a summary of work that lives in EvalOS. Sales and Marketing have no EvalOS
  // work — the opportunity is GHL's until it is won — so a tile page would be a summary of one
  // screen, sitting in front of that screen. They land on the thing itself, via
  // `homePathFor` → `boardPathFor` → `/opportunities/board`.
  //
  // These two entries are therefore not the route they arrive by: `/dashboard` is gated to
  // `PRODUCTION_ROLES` and they are not in it. They are here because the map is exhaustive by
  // design — `Record<Role, …>` is what made the compiler list every place Unit 38 had to
  // touch — and because the honest answer to "what would they see" is their board, not a
  // crash. If `/dashboard` is ever opened to them, this is already right.
  SALES: <OpportunityBoardPage />,
  MARKETING: <OpportunityBoardPage />,
}

export default function RoleDashboard() {
  return DASHBOARDS[useMe().role]
}
