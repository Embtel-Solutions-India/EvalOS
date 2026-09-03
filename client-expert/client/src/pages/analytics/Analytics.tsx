import { useQuery } from '@tanstack/react-query'
import { BarChart3, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { REQUEST_STATUS_LABELS, listRequests } from '@/services/intakeService'
import { listPayments } from '@/services/paymentService'
import type { IntakeDocumentStatus, RequestStatus } from '@/types/intake'
import { formatCurrency } from '@shared/utils/formatters'

// Sequential single-hue ramp (light -> dark navy) for the request lifecycle —
// stages are an ordered progression, not independent categories, so a
// sequential ramp communicates "further along" better than distinct hues.
const REQUEST_STATUS_ORDER: RequestStatus[] = [
  'received',
  'information_review',
  'documents_review',
  'in_progress',
  'final_review',
  'completed',
]
const REQUEST_STATUS_COLOR: Record<RequestStatus, string> = {
  received: 'hsl(204 40% 78%)',
  information_review: 'hsl(204 55% 65%)',
  documents_review: 'hsl(204 70% 52%)',
  in_progress: 'hsl(204 85% 40%)',
  final_review: 'hsl(204 100% 28%)',
  completed: 'hsl(204 100% 16%)',
}

// Document statuses reuse the app's existing status semantics (the same
// colors IntakeDocumentStatusBadge already uses) so the chart reads
// consistently with the badges shown everywhere else.
const DOCUMENT_STATUS_ORDER: IntakeDocumentStatus[] = ['required', 'uploaded', 'processing', 'needs_attention', 'accepted']
const DOCUMENT_STATUS_LABEL: Record<IntakeDocumentStatus, string> = {
  required: 'Required',
  uploaded: 'Uploaded',
  processing: 'Processing',
  needs_attention: 'Needs Attention',
  accepted: 'Accepted',
}
const DOCUMENT_STATUS_COLOR: Record<IntakeDocumentStatus, string> = {
  required: 'hsl(var(--muted-foreground))',
  uploaded: 'hsl(var(--info))',
  processing: 'hsl(var(--warning))',
  needs_attention: 'hsl(var(--destructive))',
  accepted: 'hsl(var(--success))',
}

const PAYMENT_STATUS_LABEL: Record<string, string> = {
  pending: 'Pending',
  paid: 'Paid',
  failed: 'Failed',
  refunded: 'Refunded',
}
const PAYMENT_STATUS_COLOR: Record<string, string> = {
  pending: 'hsl(var(--warning))',
  paid: 'hsl(var(--success))',
  failed: 'hsl(var(--destructive))',
  refunded: 'hsl(var(--muted-foreground))',
}

function monthLabel(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
}

function ChartCard({
  title,
  description,
  empty,
  footer,
  children,
}: {
  title: string
  description: string
  empty: boolean
  footer?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <p className="text-sm text-muted-foreground">{description}</p>
      </CardHeader>
      <CardContent>
        {empty ? (
          <p className="py-10 text-center text-sm text-muted-foreground">Nothing to show yet.</p>
        ) : (
          <>
            <div className="h-64 w-full">{children}</div>
            {footer}
          </>
        )}
      </CardContent>
    </Card>
  )
}

export default function Analytics() {
  const { data: requests, isLoading: requestsLoading } = useQuery({ queryKey: ['requests'], queryFn: listRequests })
  const { data: payments, isLoading: paymentsLoading } = useQuery({ queryKey: ['payments'], queryFn: listPayments })
  const isLoading = requestsLoading || paymentsLoading

  if (isLoading) {
    return (
      <div>
        <PageHeader title="Analytics" description="A quick overview of your requests, documents, and payments." />
        <div>
          <ListSkeleton />
        </div>
      </div>
    )
  }

  const allRequests = requests ?? []
  const allPayments = payments ?? []
  const allDocuments = allRequests.flatMap((request) => request.documents)

  if (allRequests.length === 0 && allPayments.length === 0) {
    return (
      <div>
        <PageHeader title="Analytics" description="A quick overview of your requests, documents, and payments." />
        <EmptyState
          icon={BarChart3}
          title="Nothing to analyze yet"
          description="Once you start a request, your status breakdown and activity will show up here."
          action={
            <Button asChild>
              <Link to="/start/service">
                <Plus className="h-4 w-4" />
                Start a New Request
              </Link>
            </Button>
          }
        />
      </div>
    )
  }

  const requestStatusData = REQUEST_STATUS_ORDER.map((status) => ({
    status,
    name: REQUEST_STATUS_LABELS[status],
    value: allRequests.filter((request) => request.status === status).length,
    fill: REQUEST_STATUS_COLOR[status],
  })).filter((entry) => entry.value > 0)

  const documentStatusData = DOCUMENT_STATUS_ORDER.map((status) => ({
    status,
    name: DOCUMENT_STATUS_LABEL[status],
    count: allDocuments.filter((document) => document.status === status).length,
    fill: DOCUMENT_STATUS_COLOR[status],
  })).filter((entry) => entry.count > 0)

  const monthlyMap = new Map<string, number>()
  for (const request of allRequests) {
    const label = monthLabel(request.createdAt)
    monthlyMap.set(label, (monthlyMap.get(label) ?? 0) + 1)
  }
  const monthlyActivityData = Array.from(monthlyMap.entries())
    .map(([month, count]) => ({ month, count }))
    .sort((a, b) => new Date(a.month).getTime() - new Date(b.month).getTime())

  const paymentsData = allPayments
    .slice()
    .sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime())
    .map((payment) => ({
      label: monthLabel(payment.date),
      amount: payment.amount,
      status: payment.status,
      fill: PAYMENT_STATUS_COLOR[payment.status],
    }))

  return (
    <div>
      <PageHeader title="Analytics" description="A quick overview of your requests, documents, and payments." />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <ChartCard
          title="Request Status"
          description="Where your requests currently stand."
          empty={requestStatusData.length === 0}
        >
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={requestStatusData} dataKey="value" nameKey="name" innerRadius={55} outerRadius={85} paddingAngle={2}>
                {requestStatusData.map((entry) => (
                  <Cell key={entry.status} fill={entry.fill} stroke="hsl(var(--card))" strokeWidth={2} />
                ))}
              </Pie>
              <Tooltip
                formatter={(value, name) => [`${value} request${Number(value) === 1 ? '' : 's'}`, name]}
                contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 13 }}
              />
              <Legend verticalAlign="bottom" height={48} wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard
          title="Document Status"
          description="Every document across your requests, by status."
          empty={documentStatusData.length === 0}
        >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={documentStatusData} layout="vertical" margin={{ left: 16, right: 16 }}>
              <CartesianGrid horizontal={false} stroke="hsl(var(--border))" />
              <XAxis type="number" allowDecimals={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="name" width={100} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <Tooltip
                formatter={(value) => [`${value} document${Number(value) === 1 ? '' : 's'}`, 'Count']}
                contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 13 }}
                cursor={{ fill: 'hsl(var(--muted))' }}
              />
              <Bar dataKey="count" radius={[0, 4, 4, 0]} maxBarSize={28}>
                {documentStatusData.map((entry) => (
                  <Cell key={entry.status} fill={entry.fill} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard
          title="Request Activity"
          description="Requests submitted, by month."
          empty={monthlyActivityData.length === 0}
        >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={monthlyActivityData} margin={{ left: -16 }}>
              <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
              <XAxis dataKey="month" tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <YAxis allowDecimals={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <Tooltip
                formatter={(value) => [`${value} request${Number(value) === 1 ? '' : 's'}`, 'Submitted']}
                contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 13 }}
                cursor={{ fill: 'hsl(var(--muted))' }}
              />
              <Bar dataKey="count" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} maxBarSize={40} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard
          title="Payments"
          description="Payment amounts by month, colored by status."
          empty={paymentsData.length === 0}
          footer={
            <div className="mt-3 flex flex-wrap justify-center gap-x-4 gap-y-1.5">
              {Object.entries(PAYMENT_STATUS_LABEL).map(([status, label]) => (
                <span key={status} className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: PAYMENT_STATUS_COLOR[status] }} />
                  {label}
                </span>
              ))}
            </div>
          }
        >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={paymentsData} margin={{ left: -16 }}>
              <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
              <XAxis dataKey="label" tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} tickFormatter={(value) => formatCurrency(Number(value))} />
              <Tooltip
                formatter={(value, _name, item) => [
                  formatCurrency(Number(value)),
                  PAYMENT_STATUS_LABEL[(item?.payload as { status?: string } | undefined)?.status ?? ''] ?? 'Amount',
                ]}
                contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 13 }}
                cursor={{ fill: 'hsl(var(--muted))' }}
              />
              <Bar dataKey="amount" radius={[4, 4, 0, 0]} maxBarSize={40}>
                {paymentsData.map((entry, index) => (
                  <Cell key={index} fill={entry.fill} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>
      </div>
    </div>
  )
}
