import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Wallet } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { statusOf } from '@shared/services/apiClient'
import { formatDate } from '@shared/utils/formatters'
import { expertFailureMessage, PAYOUT_STATUS, payoutTotals } from '@/lib/expertCase'
import { confirmPayment, listPayouts } from '@/services/expertPortalService'

const money = (amount: number, currency: string) =>
  new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)

/**
 * What the Expert Network Manager has recorded for this expert (spec 62). The ENM records payments
 * by hand on the staff screens (D59); a recorded transfer stays **Processing** until the expert
 * confirms receipt here (Unit 63), which is the one write on this page.
 */
export default function Payouts() {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'payouts'],
    queryFn: listPayouts,
    retry: false,
  })

  async function confirm(paymentId: string) {
    setConfirming(paymentId)
    setFailure(null)
    try {
      queryClient.setQueryData(['expert-portal', 'payouts'], await confirmPayment(paymentId))
    } catch (failed) {
      setFailure(expertFailureMessage(statusOf(failed)))
    } finally {
      setConfirming(null)
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        title="Payouts"
        description="What has been recorded as owed or paid to you. When a transfer reaches you, confirm it here. Questions go to the Expert Network Manager."
      />
      {failure && <p role="alert" className="mb-4 text-sm text-destructive">{failure}</p>}

      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />}
      {data?.length === 0 && (
        <EmptyState icon={Wallet} title="No payouts yet" description="A payout appears here once a signed letter is recorded for payment." />
      )}

      {data && data.length > 0 && (
        <>
          <div className="mb-6 grid gap-3 sm:grid-cols-3">
            {payoutTotals(data).flatMap(([currency, t]) => [
              <Card key={`${currency}-owed`} className="p-4">
                <p className="text-sm text-muted-foreground">Owed to you ({currency})</p>
                <p className="mt-1 text-3xl font-semibold tabular-nums text-warning">{money(t.owed, currency)}</p>
              </Card>,
              <Card key={`${currency}-processing`} className="p-4">
                <p className="text-sm text-muted-foreground">Sent, waiting for you ({currency})</p>
                <p className="mt-1 text-3xl font-semibold tabular-nums text-foreground">{money(t.processing, currency)}</p>
              </Card>,
              <Card key={`${currency}-paid`} className="p-4">
                <p className="text-sm text-muted-foreground">Paid to you ({currency})</p>
                <p className="mt-1 text-3xl font-semibold tabular-nums text-success">{money(t.paid, currency)}</p>
              </Card>,
            ])}
          </div>

          <Card className="overflow-hidden">
            <div className="hidden grid-cols-[minmax(0,1fr)_9rem_7rem_9rem_10rem] gap-4 border-b bg-muted/50 px-4 py-2 text-xs font-medium text-muted-foreground sm:grid">
              <span>Case</span>
              <span className="text-right">Amount</span>
              <span>Status</span>
              <span>Sent on</span>
              <span />
            </div>
            <ul className="divide-y">
              {data.map((row, i) => {
                const status = PAYOUT_STATUS[row.status]
                return (
                  <li
                    key={`${row.caseReference}-${i}`}
                    className="grid grid-cols-2 gap-x-4 gap-y-1 px-4 py-3 text-sm sm:grid-cols-[minmax(0,1fr)_9rem_7rem_9rem_10rem] sm:items-center"
                  >
                    <span className="font-medium text-foreground">{row.caseReference ?? 'Case'}</span>
                    <span className="text-right tabular-nums text-foreground">{money(row.amount, row.currency)}</span>
                    <span><Badge variant={status.variant}>{status.label}</Badge></span>
                    <span className="text-right text-muted-foreground sm:text-left">{row.settledOn ? formatDate(row.settledOn) : '—'}</span>
                    <span className="col-span-2 sm:col-span-1 sm:text-right">
                      {/* One transfer may settle several cases; confirming it confirms all of them. */}
                      {row.status === 'PAID' && row.paymentId && (
                        <Button
                          size="sm"
                          disabled={confirming !== null}
                          onClick={() => void confirm(row.paymentId!)}
                        >
                          {confirming === row.paymentId ? 'Confirming…' : 'Confirm received'}
                        </Button>
                      )}
                    </span>
                  </li>
                )
              })}
            </ul>
          </Card>
        </>
      )}
    </div>
  )
}
