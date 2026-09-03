import { useQuery } from '@tanstack/react-query'
import { Wallet } from 'lucide-react'
import { Card } from '@shared/components/ui/card'
import { Pagination } from '@shared/components/ui/pagination'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { PaymentStatusBadge } from '@/components/common/StatusBadge'
import { usePagination } from '@/hooks/usePagination'
import { listPayments } from '@/services/paymentService'
import { formatCurrency, formatDateShort } from '@shared/utils/formatters'

export default function Payments() {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['payments'], queryFn: listPayments })
  const { page, pageCount, pageItems, setPage } = usePagination(data ?? [])

  const totalPaid = (data ?? []).filter((p) => p.status === 'paid').reduce((sum, p) => sum + p.amount, 0)
  const totalPending = (data ?? []).filter((p) => p.status === 'pending').reduce((sum, p) => sum + p.amount, 0)

  return (
    <div>
      <PageHeader title="Payments" description="A summary of payments made toward your evaluations." />

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Card className="p-5 sm:p-6">
            <p className="text-sm text-muted-foreground">Total Paid</p>
            <p className="mt-1 text-2xl font-semibold text-success">{formatCurrency(totalPaid)}</p>
          </Card>
          <Card className="p-5 sm:p-6">
            <p className="text-sm text-muted-foreground">Pending</p>
            <p className="mt-1 text-2xl font-semibold text-warning">{formatCurrency(totalPending)}</p>
          </Card>
        </div>
      )}

      {isLoading && <TableSkeleton />}
      {isError && <ErrorState description="We couldn't load your payments." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState icon={Wallet} title="No Payments" description="Your payment history will appear here." />
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <Card className="overflow-hidden p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Payment</TableHead>
                <TableHead>Application</TableHead>
                <TableHead>Amount</TableHead>
                <TableHead>Date</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Invoice</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageItems.map((payment) => (
                <TableRow key={payment.id}>
                  <TableCell className="font-medium text-foreground">{payment.id.toUpperCase()}</TableCell>
                  <TableCell>{payment.applicationReference}</TableCell>
                  <TableCell>{formatCurrency(payment.amount, payment.currency)}</TableCell>
                  <TableCell>{formatDateShort(payment.date)}</TableCell>
                  <TableCell>
                    <PaymentStatusBadge status={payment.status} />
                  </TableCell>
                  <TableCell>{payment.invoiceId ? payment.invoiceId.toUpperCase() : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="border-t p-4">
            <Pagination page={page} pageCount={pageCount} onPageChange={setPage} />
          </div>
        </Card>
      )}
    </div>
  )
}
