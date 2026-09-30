import { useNavigate } from 'react-router-dom'

import { Card } from '../../components/ui/card'
import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'

import { NewDealFields } from './NewDealForm'
import NewLeadForm from './NewLeadForm'
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
  // Unit 63: the ENM's "Add candidate" is this same upsert, on their hiring pipeline — and keeps
  // the short form: the intake questions are about a client's case, not a candidate.
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
        <h1 className="text-xl font-semibold text-slate-900">Add lead</h1>
        <p className="text-sm text-slate-500">Opens the opportunity in GoHighLevel, on your own pipeline.</p>
      </header>
      <Card title="" state={state}>
        <div className="p-4">
          <NewDealFields lead columns={data?.columns ?? []} onCreated={() => navigate('/opportunities/board')} />
        </div>
      </Card>
    </section>
  )
}

function NewCandidatePage() {
  const navigate = useNavigate()
  const candidate = true

  return (
    <section className="max-w-3xl space-y-4">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">{candidate ? 'Add candidate' : 'Add lead'}</h1>
        <p className="text-sm text-slate-500">
          {candidate
            ? 'Captures the candidate and opens them on the expert hiring pipeline in GoHighLevel.'
            : 'Captures the contact and opens a lead on your own pipeline in GoHighLevel.'}
        </p>
      </header>

      {/* No `state`: this screen loads nothing. The lead form owns its own submitting and error
          state, so a skeleton here would be a loading indicator for a request that never happens. */}
      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <NewLeadForm
          candidate={candidate}
          onOpened={() => navigate(candidate ? '/hiring' : '/opportunities/board')}
        />
      </div>
    </section>
  )
}
