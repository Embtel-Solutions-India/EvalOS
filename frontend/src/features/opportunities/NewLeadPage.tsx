import { useNavigate } from 'react-router-dom'

import { useMe } from '../../lib/authContext'

import NewLeadForm from './NewLeadForm'

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
  const navigate = useNavigate()
  // Unit 63: the ENM's "Add candidate" is this same upsert, on their hiring pipeline.
  const candidate = useMe().role === 'EXPERT_NETWORK_MANAGER'

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
