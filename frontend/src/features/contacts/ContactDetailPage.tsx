import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Briefcase, CheckCircle2, Handshake, User } from 'lucide-react'

import { Panel } from '../../components/ui/panel'
import { formatMoney } from '../../lib/money'
import { STAGE_COLUMNS } from '../board/boardRules'
import { useMetrics } from '../dashboards/useMetrics'
import {
  fetchContactDetail,
  sourceLabel,
  type ContactCase,
  type ContactDetail,
} from '../opportunities/opportunityApi'

/**
 * One contact: who they are, their opportunities, their cases in progress and the ones delivered.
 *
 * Scoped by the server exactly as the list is — a desk sees only the deals on its pipelines and no
 * case at all (D19c), so the case panels are absent for it rather than empty.
 */
export default function ContactDetailPage() {
  const { contactId = '' } = useParams()
  const { data, state } = useMetrics<ContactDetail>((signal) => fetchContactDetail(contactId, signal), [contactId])

  const cases = data?.cases
  const delivered = cases?.filter(isDelivered) ?? []
  const current = cases?.filter((c) => !isDelivered(c)) ?? []

  return (
    <section className="space-y-4">
      <Link to="/contacts" className="inline-flex items-center gap-1 text-sm hover:underline" style={{ color: 'var(--text-muted)' }}>
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
        Contacts
      </Link>

      {state.kind === 'error' && (
        <p role="alert" className="text-sm" style={{ color: 'var(--status-red)' }}>
          {state.note}
        </p>
      )}
      {state.kind === 'loading' && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading…
        </p>
      )}

      {data && (
        <>
          <header>
            <h1 className="text-xl font-semibold tracking-tight">{data.contact.fullName?.trim() || 'Unnamed contact'}</h1>
            {data.contact.brandName && (
              <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
                {data.contact.brandName}
              </p>
            )}
          </header>

          <Panel title="Details" icon={<User />}>
            <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <Fact label="Email">
                {data.contact.email ? (
                  <a href={`mailto:${data.contact.email}`} className="hover:underline" style={{ color: 'var(--accent-primary)' }}>
                    {data.contact.email}
                  </a>
                ) : (
                  '—'
                )}
              </Fact>
              <Fact label="Phone">{data.contact.phone || '—'}</Fact>
              <Fact label="Company">{data.contact.company || '—'}</Fact>
              <Fact label="Source">{sourceLabel(data.contact.sourceChannel) ?? '—'}</Fact>
              <Fact label="Opportunities">{data.deals.length}</Fact>
              {cases && <Fact label="Cases">{cases.length}</Fact>}
            </dl>
          </Panel>

          <Panel title="Opportunities" icon={<Handshake />}>
            {data.deals.length === 0 ? (
              <Empty>No opportunities.</Empty>
            ) : (
              <div className="overflow-x-auto">
                <table className="tbl">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Pipeline</th>
                      <th>Stage</th>
                      <th>Status</th>
                      <th className="num">Value</th>
                      <th>Created</th>
                      <th>Last activity</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.deals.map((deal, index) => (
                      <tr key={deal.ghlId ?? index}>
                        <td className="font-medium">
                          {deal.ghlId ? (
                            <Link to={`/opportunities/${deal.ghlId}`} className="hover:underline" style={{ color: 'var(--accent-primary)' }}>
                              {deal.name?.trim() || 'Untitled deal'}
                            </Link>
                          ) : (
                            deal.name?.trim() || 'Untitled deal'
                          )}
                        </td>
                        <td>{deal.pipelineName ?? '—'}</td>
                        <td>{deal.stageName ?? '—'}</td>
                        <td>{deal.status ? <span className="chip">{deal.status}</span> : '—'}</td>
                        <td className="num font-num">{deal.amount == null ? '—' : formatMoney(deal.amount)}</td>
                        <td className="whitespace-nowrap">{day(deal.createdAt)}</td>
                        <td className="whitespace-nowrap">{day(deal.updatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {cases && (
            <>
              <Panel title="Recent cases" icon={<Briefcase />}>
                <CaseTable cases={current} empty="No case in progress." dateLabel="Deadline" date={(c) => c.deadline} />
              </Panel>
              <Panel title="Delivered cases" icon={<CheckCircle2 />}>
                <CaseTable cases={delivered} empty="Nothing delivered yet." dateLabel="Delivered" date={(c) => c.deliveryDate} />
              </Panel>
            </>
          )}
        </>
      )}
    </section>
  )
}

function CaseTable({
  cases,
  empty,
  dateLabel,
  date,
}: {
  cases: readonly ContactCase[]
  empty: string
  dateLabel: string
  date: (c: ContactCase) => string | null
}) {
  if (cases.length === 0) return <Empty>{empty}</Empty>
  return (
    <div className="overflow-x-auto">
      <table className="tbl">
        <thead>
          <tr>
            <th>Case</th>
            <th>Service</th>
            <th>Stage</th>
            <th className="num">Value</th>
            <th>Opened</th>
            <th>{dateLabel}</th>
          </tr>
        </thead>
        <tbody>
          {cases.map((c) => (
            <tr key={c.id}>
              <td className="font-medium">
                <Link to={`/cases/${c.id}`} className="hover:underline" style={{ color: 'var(--accent-primary)' }}>
                  {c.caseCode}
                </Link>
              </td>
              <td>{[c.serviceType, c.serviceSubtype].filter(Boolean).map((s) => humanize(s!)).join(' · ') || '—'}</td>
              <td>
                <span className="chip">{stageLabel(c.stage)}</span>
              </td>
              <td className="num font-num">{c.dealValue == null ? '—' : formatMoney(c.dealValue)}</td>
              <td className="whitespace-nowrap">{day(c.createdAt)}</td>
              <td className="whitespace-nowrap">{day(date(c))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

function Empty({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
      {children}
    </p>
  )
}

/** Delivered work: the two outcome stages, or a delivery date already set. */
function isDelivered(c: ContactCase): boolean {
  return c.stage === 'DELIVERED' || c.stage === 'CLOSED' || c.deliveryDate != null
}

function stageLabel(stage: string): string {
  return STAGE_COLUMNS.find((column) => (column.stages as readonly string[]).includes(stage))?.label ?? humanize(stage)
}

function humanize(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}
