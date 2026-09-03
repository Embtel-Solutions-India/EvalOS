import { useQuery } from '@tanstack/react-query'
import { Download, Receipt } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Pagination } from '@shared/components/ui/pagination'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { InvoiceStatusBadge } from '@/components/common/StatusBadge'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { usePagination } from '@/hooks/usePagination'
import { listInvoices } from '@/services/paymentService'
import { formatCurrency, formatDateShort } from '@shared/utils/formatters'

export default function Invoices() {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['invoices'], queryFn: listInvoices })
  const { page, pageCount, pageItems, setPage } = usePagination(data ?? [])

  return (
    <div>
      <PageHeader title="Invoices" description="Download and review invoices issued for your evaluations." />

      {isLoading && <TableSkeleton />}
      {isError && <ErrorState description="We couldn't load your invoices." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState icon={Receipt} title="No Invoices" description="Your invoices will appear here once issued." />
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <Card className="overflow-hidden p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Invoice Number</TableHead>
                <TableHead>Application</TableHead>
                <TableHead>Date</TableHead>
                <TableHead>Amount</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageItems.map((invoice) => (
                <TableRow key={invoice.id}>
                  <TableCell className="font-medium text-foreground">{invoice.invoiceNumber}</TableCell>
                  <TableCell>{invoice.applicationReference}</TableCell>
                  <TableCell>{formatDateShort(invoice.date)}</TableCell>
                  <TableCell>{formatCurrency(invoice.amount, invoice.currency)}</TableCell>
                  <TableCell>
                    <InvoiceStatusBadge status={invoice.status} />
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="sm" onClick={() => toast.info('Invoice preview is a mock in this development phase.')}>
                        View
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => toast.success('Invoice PDF download started.')}>
                        <Download className="h-3.5 w-3.5" />
                        PDF
                      </Button>
                    </div>
                  </TableCell>
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
