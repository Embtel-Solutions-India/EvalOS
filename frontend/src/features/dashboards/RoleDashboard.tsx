import type { ReactNode } from 'react'
import { useMe } from '../../lib/authContext'
import type { Role } from '../../lib/session'
import CaseManagerDashboard from './CaseManagerDashboard'
import CoordinatorDashboard from './CoordinatorDashboard'
import ExpertNetworkDashboard from './ExpertNetworkDashboard'
import GmDashboard from './GmDashboard'
import PipelineDashboard from './PipelineDashboard'
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
 * **The GM and Brand Manager no longer share a screen, and the reason is data rather than
 * taste.** They shared `RevenueDashboard` on the argument that the difference between them was a
 * filter — the GM's payload carried a per-brand breakdown, the Brand Manager's was their brand.
 * That held while every figure on it was brand-scoped. The GM's overview now reads the GHL
 * location as well, which is invariant 1's one stated exception and is licensed *only* while the
 * reader is the cross-brand role: a Brand Manager shown a pipeline figure cannot tell, and
 * neither can the server, whether it is theirs. So the GM lands on `GmDashboard` and the Brand
 * Manager keeps `RevenueDashboard`, which is brand-scoped and true.
 *
 * They were never the same role with a filter in any case — `SEES_STRATEGY_NOTES` excludes the
 * Brand Manager, so nothing on a shared screen could assume oversight meant full visibility.
 */
const DASHBOARDS: Record<Role, ReactNode> = {
  GM: <GmDashboard />,
  BRAND_MANAGER: <RevenueDashboard />,
  PROJECT_MANAGER: <PmDashboard />,
  PROJECT_COORDINATOR: <CoordinatorDashboard />,
  CASE_MANAGER: <CaseManagerDashboard />,
  EXPERT_NETWORK_MANAGER: <ExpertNetworkDashboard />,
  // **They had no dashboard until 2026-09-14, and the argument for that has been narrowed
  // rather than thrown away.** It ran: every other role lands on a summary of work that lives in
  // EvalOS; Sales and Marketing have none, because the opportunity is GHL's until it is won; so a
  // tile page would be a summary of the one screen sitting behind it.
  //
  // That holds for anything derived by counting cards, and it is why `PipelineDashboard` is not a
  // scoreboard. It does not hold for **age**: a Kanban groups deals by stage in GHL's order and
  // has no notion of how long a card has sat there, so the deals quietly dying are invisible on
  // the board by construction. One question the board cannot answer is worth one screen.
  //
  // `/dashboard` is still gated to `PRODUCTION_ROLES`, so these two do not arrive here yet —
  // `homePathFor` sends them to `/opportunities/board`. Changing that is a nav decision, taken in
  // `navigation.ts`; this map stays exhaustive either way, which is what made the compiler list
  // every place Unit 38 had to touch.
  SALES: <PipelineDashboard audience="sales" />,
  MARKETING: <PipelineDashboard audience="marketing" />,
}

export default function RoleDashboard() {
  return DASHBOARDS[useMe().role]
}
