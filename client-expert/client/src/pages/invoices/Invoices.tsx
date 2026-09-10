import { useQuery } from '@tanstack/react-query'
import { Receipt } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { failureMessage, NO_TOKEN, type ClientInvoice } from '@shared/lib/portal'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { statusOf } from '@shared/services/apiClient'
import { listInvoices } from '@/services/invoiceService'
import { formatDateShort } from '@shared/utils/formatters'

/**
 * The client's invoices and what has been paid (Unit 41).
 *
 * **A window onto GHL, not a second copy of it.** Sales raises invoices in GHL, GHL's QuickBooks
 * integration does the accounting, and EvalOS reads the outcome — it stores no invoice fact at
 * all. Nothing on this page can be changed from here, and there is deliberately no "pay now"
 * button: payment goes through GHL's own link.
 *
 * **Outside the account shell, like `Documents`.** The credential is a scoped portal token, and
 * this one must be **party-scoped** — it names the client, not one engagement, because an
 * invoice belongs to the person who may have several cases. A case-scoped link answers 403 and
 * the message below says why in the client's language.
 *
 * **Every status word is GHL's.** Nothing here computes whether something is settled: `paid`,
 * `unpaid`, `partially_paid` and the rest come off the payload. A screen that decided for itself
 * would be a second opinion about money.
 */
export default function Invoices() {
  const tokenPresent = usePortalToken()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'invoices'],
    queryFn: listInvoices,
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Your invoices" description={NO_TOKEN} />
      </div>
    )
  }

  const status = statusOf(error)

  return (
    <div className="mx-auto max-w-4xl p-6">
      <PageHeader
        title="Your invoices"
        description="What you have been billed, and what has been received."
      />

      {isLoading && <TableSkeleton />}

      {isError && (
        <ErrorState
          description={
            // A 403 here has one specific cause worth naming: the link opened is scoped to a
            // single case, and billing belongs to the client. The generic message would send
            // them to support for something a different link fixes.
            status === 403
              ? 'This link opens one case rather than your account, so it cannot show your billing. Ask us for your account link.'
              : failureMessage(status)
          }
          onRetry={() => void refetch()}
        />
      )}

      {!isLoading && !isError && data && (
        data.length === 0 ? (
          <EmptyState
            icon={Receipt}
            title="No invoices yet"
            description="Anything you are billed will appear here."
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Invoice</TableHead>
                <TableHead>Issued</TableHead>
                <TableHead>Due</TableHead>
                <TableHead className="text-right">Total</TableHead>
                <TableHead className="text-right">Outstanding</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((invoice) => (
                <InvoiceRow key={invoice.invoiceNumber ?? crypto.randomUUID()} invoice={invoice} />
              ))}
            </TableBody>
          </Table>
        )
      )}
    </div>
  )
}

function InvoiceRow({ invoice }: { invoice: ClientInvoice }) {
  return (
    <TableRow>
      <TableCell className="font-medium">{invoice.invoiceNumber ?? '—'}</TableCell>
      <TableCell>{invoice.issueDate ? formatDateShort(invoice.issueDate) : '—'}</TableCell>
      <TableCell>{invoice.dueDate ? formatDateShort(invoice.dueDate) : '—'}</TableCell>
      <TableCell className="text-right">{money(invoice.total, invoice.currency)}</TableCell>
      <TableCell className="text-right">{money(invoice.amountDue, invoice.currency)}</TableCell>
      <TableCell>
        {/*
          GHL's own word, tidied for reading but never reinterpreted. Deciding here that
          `partially_paid` means "unpaid" would be EvalOS forming a second opinion about money.
        */}
        <Badge variant="secondary">{(invoice.status ?? 'unknown').replace(/_/g, ' ')}</Badge>
      </TableCell>
    </TableRow>
  )
}

/**
 * An amount, or an em dash.
 *
 * Null is shown as "—" rather than as 0.00: "not recorded" and "nothing owed" are different
 * facts, and on an invoice the second one is a claim nobody should make on GHL's behalf.
 */
function money(amount: number | null, currency: string | null): string {
  if (amount === null || amount === undefined) return '—'
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: currency ?? 'USD',
  }).format(amount)
}
