import { useNavigate } from 'react-router-dom'

import { Card } from '../../components/ui/card'
import { useMetrics } from '../dashboards/useMetrics'
import { NewDealFields } from './NewDealForm'
import { fetchOpportunityBoard, type OpportunityBoard } from './opportunityApi'

/**
 * Opening a deal, as its own screen.
 *
 * <p><strong>The sidebar is the way in, not a button on the board</strong> (2026-09-17). It was a
 * button above the cards, which put the one thing a salesperson opens the app to do behind first
 * finding the board and then finding the button on it. A nav entry is one click from anywhere.
 *
 * <p><strong>The board is still read here, for its stages.</strong> The form offers the stages of
 * the caller's own pipeline, and those come from the same board payload rather than a second
 * endpoint — one question, one source, and no chance of the two disagreeing about which pipeline
 * is "mine".
 */
export default function NewDealPage() {
  const navigate = useNavigate()
  const { data, state } = useMetrics<OpportunityBoard>((signal) => fetchOpportunityBoard(signal), [])

  return (
    <section className="max-w-3xl space-y-4">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">Add opportunity</h1>
        <p className="text-sm text-slate-500">
          Opens the deal in GoHighLevel, on your own pipeline.
        </p>
      </header>

      <Card title="" state={state}>
        <div className="p-4">
          {/* Back to the board once it lands: the deal now exists somewhere the salesperson can
              see it, and leaving them on an empty form would read as if nothing had happened. */}
          <NewDealFields
            columns={data?.columns ?? []}
            onCreated={() => navigate('/opportunities/board')}
          />
        </div>
      </Card>
    </section>
  )
}
