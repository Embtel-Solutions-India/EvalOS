import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { DialogContent, DialogRoot } from '../../components/ui/dialog'
import { sourceLabel } from '../opportunities/opportunityApi'
import { fetchCaseDeal } from './caseApi'

/**
 * The opportunity the case was won from, in a pop-up (read-only), opened from the case header.
 * Same facts as the opportunity screen's details card; read through the case, so the staff who work
 * it — not only the desk holding the deal's pipeline — can open it.
 */
export default function CaseDealDialog({
  caseId,
  open,
  onOpenChange,
}: {
  caseId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const deal = useQuery({
    queryKey: ['case', caseId, 'deal'],
    queryFn: ({ signal }) => fetchCaseDeal(caseId, signal),
    enabled: open,
  })
  const d = deal.data

  return (
    <DialogRoot open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Opportunity details" description="The deal this case was won from.">
        {deal.isPending && <p className="mt-3 text-sm">Loading…</p>}
        {deal.isError && (
          <p role="alert" className="mt-3 text-sm" style={{ color: 'var(--status-red)' }}>
            Could not load the opportunity.
          </p>
        )}
        {deal.isSuccess && !d && (
          <p className="mt-3 text-sm" style={{ color: 'var(--text-muted)' }}>
            This case has no opportunity in EvalOS.
          </p>
        )}
        {d && (
          <dl className="mt-4 grid gap-4 sm:grid-cols-2">
            <Fact label="Name" value={d.name} />
            <Fact label="Email" value={d.email} href={d.email ? `mailto:${d.email}` : undefined} />
            <Fact label="Phone" value={d.phone} href={d.phone ? `tel:${d.phone.replace(/[^\d+]/g, '')}` : undefined} />
            <Fact label="Company" value={d.company} />
            {d.dealFields.map((f) => (
              <Fact key={f.label} label={f.label} value={f.value} />
            ))}
            <Fact label="Source" value={sourceLabel(d.source)} />
            <Fact label="Assigned to" value={d.assignedTo} />
            <Fact label="Created on" value={d.createdAt ? new Date(d.createdAt).toLocaleDateString() : null} />
            <Fact label="Country" value={d.country} />
            <Fact label="Tags" value={d.tags.length ? d.tags.join(', ') : null} />
            {d.contactFields.map((f) => (
              <Fact key={f.label} label={f.label} value={f.value} />
            ))}
          </dl>
        )}
      </DialogContent>
    </DialogRoot>
  )
}

function Fact({ label, value, href }: { label: string; value: string | null; href?: string }): ReactNode {
  return (
    <div className="min-w-0">
      <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd className="text-sm break-words">
        {!value?.trim() ? '—' : href ?
          <a href={href} className="hover:underline" style={{ color: 'var(--accent-primary)' }}>
            {value}
          </a>
        : value}
      </dd>
    </div>
  )
}
