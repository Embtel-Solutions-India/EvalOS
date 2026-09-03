import { useQuery } from '@tanstack/react-query'
import { Wallet } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { Card } from '@shared/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { EmptyState } from '@shared/components/common/EmptyState'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { listCases } from '@/services/expertCaseService'
import { formatCurrency, formatDateShort } from '@shared/utils/formatters'

function startOfWeek(date: Date): Date {
  const result = new Date(date)
  const day = result.getDay()
  const diff = (day === 0 ? -6 : 1) - day // shift to Monday
  result.setDate(result.getDate() + diff)
  result.setHours(0, 0, 0, 0)
  return result
}

function nextPaymentDate(): Date {
  const result = new Date()
  const daysUntilFriday = (5 - result.getDay() + 7) % 7 || 7
  result.setDate(result.getDate() + daysUntilFriday)
  return result
}

export default function ExpertPayments() {
  const { data, isLoading } = useQuery({ queryKey: ['expert-cases'], queryFn: listCases })
  const cases = data ?? []

  const now = new Date()
  const weekStart = startOfWeek(now)
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1)

  const pendingPayout = cases.filter((c) => c.paymentStatus === 'pending').reduce((sum, c) => sum + c.amount, 0)
  const paidThisWeek = cases
    .filter((c) => c.paymentStatus === 'paid' && c.paymentDate && new Date(c.paymentDate) >= weekStart)
    .reduce((sum, c) => sum + c.amount, 0)
  const paidThisMonth = cases
    .filter((c) => c.paymentStatus === 'paid' && c.paymentDate && new Date(c.paymentDate) >= monthStart)
    .reduce((sum, c) => sum + c.amount, 0)
  const totalEarnings = cases.filter((c) => c.paymentStatus === 'paid').reduce((sum, c) => sum + c.amount, 0)

  const history = cases
    .slice()
    .sort((a, b) => {
      const aTime = a.paymentDate ? new Date(a.paymentDate).getTime() : Number.POSITIVE_INFINITY
      const bTime = b.paymentDate ? new Date(b.paymentDate).getTime() : Number.POSITIVE_INFINITY
      return bTime - aTime
    })

  return (
    <div>
      <PageHeader title="Payments" description="What you're owed and what's already been paid." />

      {isLoading && <TableSkeleton />}

      {!isLoading && cases.length === 0 && (
        <EmptyState icon={Wallet} title="No payment history yet" description="Payments for your completed cases will appear here." />
      )}

      {!isLoading && cases.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <Card className="p-5 sm:p-6">
              <p className="text-sm text-muted-foreground">Pending Payout</p>
              <p className="mt-1 text-2xl font-semibold text-warning">{formatCurrency(pendingPayout)}</p>
            </Card>
            <Card className="p-5 sm:p-6">
              <p className="text-sm text-muted-foreground">Paid This Week</p>
              <p className="mt-1 text-2xl font-semibold text-success">{formatCurrency(paidThisWeek)}</p>
            </Card>
            <Card className="p-5 sm:p-6">
              <p className="text-sm text-muted-foreground">Paid This Month</p>
              <p className="mt-1 text-2xl font-semibold text-success">{formatCurrency(paidThisMonth)}</p>
            </Card>
            <Card className="p-5 sm:p-6">
              <p className="text-sm text-muted-foreground">Total Earnings</p>
              <p className="mt-1 text-2xl font-semibold text-foreground">{formatCurrency(totalEarnings)}</p>
            </Card>
            <Card className="p-5 sm:p-6">
              <p className="text-sm text-muted-foreground">Next Payment Date</p>
              <p className="mt-1 text-2xl font-semibold text-foreground">{formatDateShort(nextPaymentDate().toISOString())}</p>
            </Card>
          </div>

          <Card className="mt-6 overflow-hidden p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Case</TableHead>
                  <TableHead>Service</TableHead>
                  <TableHead>Amount</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Payment Date</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((expertCase) => (
                  <TableRow key={expertCase.id}>
                    <TableCell className="font-medium text-foreground">{expertCase.caseReference}</TableCell>
                    <TableCell>{expertCase.serviceType}</TableCell>
                    <TableCell>{formatCurrency(expertCase.amount)}</TableCell>
                    <TableCell>
                      <Badge variant={expertCase.paymentStatus === 'paid' ? 'success' : 'warning'}>
                        {expertCase.paymentStatus === 'paid' ? 'Paid' : 'Pending'}
                      </Badge>
                    </TableCell>
                    <TableCell>{expertCase.paymentDate ? formatDateShort(expertCase.paymentDate) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        </>
      )}
    </div>
  )
}
