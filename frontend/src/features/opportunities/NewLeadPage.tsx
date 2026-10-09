import { useNavigate } from 'react-router-dom'

import { Card } from '../../components/ui/card'
import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'

import { NewDealFields } from './NewDealForm'
import { fetchOpportunityBoard, type OpportunityBoard } from './opportunityApi'

/**
 * Capturing a lead, as its own screen.
 *
 * <p><strong>The sidebar opens the form</strong> (2026-09-17). It used to sit inline above the
 * marketing board, which meant the board had to load before a marketer could type a name into it.
 *
 * <p>A lead is an upsert on (contact, pipeline) — a repeat enquiry from the same person updates
 * the open lead rather than opening a second. That is deliberate and is the one place EvalOS does
 * it; see `open-decisions.md` Q1.
 */
export default function NewLeadPage() {
  // Unit 63: the ENM's "Add candidate" is this same upsert, on their hiring pipeline.
  return useMe().role === 'EXPERT_NETWORK_MANAGER' ? <NewCandidatePage /> : <NewBdeLeadPage />
}

/**
 * Unit 39b: the BDE's "Add lead" is GHL's full opportunity form — stage, value, expected close and
 * the intake fields — opened on their own pipeline. The board is read for its stages, as on
 * `NewDealPage`.
 */
function NewBdeLeadPage() {
  const navigate = useNavigate()
  const { data, state } = useMetrics<OpportunityBoard>((signal) => fetchOpportunityBoard(signal), [])

  return (
    <section className="max-w-3xl space-y-4">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Add lead</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Opens the opportunity in GoHighLevel, on your own pipeline.</p>
      </header>
      <Card title="" state={state}>
        <div className="p-4">
          <NewDealFields lead columns={data?.columns ?? []} onCreated={() => navigate('/opportunities/board')} />
        </div>
      </Card>
    </section>
  )
}

/**
 * Spec 83: the ENM's "Add candidate" is GHL's full opportunity form on their hiring pipeline: stage,
 * owner, expected close and the location's own fields. The board is read for the stages, as the
 * BDE's page does.
 */
function NewCandidatePage() {
  const navigate = useNavigate()
  const { data, state } = useMetrics<OpportunityBoard>((signal) => fetchOpportunityBoard(signal), [])

  return (
    <section className="max-w-3xl space-y-4">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Add candidate</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Captures the candidate and opens them on the expert hiring pipeline in GoHighLevel.
        </p>
      </header>
      <Card title="" state={state}>
        <div className="p-4">
          <NewDealFields lead candidate columns={data?.columns ?? []} onCreated={() => navigate('/hiring')} />
        </div>
      </Card>
    </section>
  )
}
