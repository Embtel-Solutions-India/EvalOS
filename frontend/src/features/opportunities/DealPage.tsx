import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ArrowLeft,
  Building2,
  CalendarDays,
  ClipboardList,
  Globe,
  Info,
  Mail,
  Pencil,
  Phone,
  Signpost,
  StickyNote,
  Tag,
  User,
  UserCheck,
  Zap,
} from 'lucide-react'
import type { ReactNode } from 'react'

import { Panel, Surface } from '../../components/ui/panel'
import { useMe } from '../../lib/authContext'
import { formatMoney } from '../../lib/money'
import { daysSince } from '../dashboards/dealAge'
import { useMetrics } from '../dashboards/useMetrics'
import DealActions from './DealActions'
import DealEditDialog from './DealEditDialog'
import DealNotes from './DealNotes'
import HiringActions from './HiringActions'
import {
  fetchDealContact,
  fetchOpportunityBoard,
  sourceLabel,
  type DealContact,
} from './opportunityApi'

/**
 * One opportunity, on its own screen.
 *
 * <p><strong>Clicking a card opens this</strong> (2026-09-17). The card used to expand in place,
 * which meant the answers, the notes and the actions all appeared inside a 16rem column between
 * two other cards — readable for a note, useless for anything longer.
 *
 * <p><strong>Two columns as of 2026-09-23.</strong> The left is the record — the header with the
 * contact's details, then an Opportunity details card with the deal's (2026-09-30) — read top to
 * bottom. The right is what you <em>do</em> — Actions, then Notes — and it stays in
 * view while the left scrolls. That split is what stopped the actions being the last thing on a
 * long page: a salesperson opening a deal to move it a stage had to scroll past the whole record
 * to reach the control.
 *
 * <p><strong>Everything here is a field something actually stores.</strong> The design this
 * follows also carried a "Hot" lead-temperature badge, questionnaires and request documents
 * (removed with the client request, Units 55 and 64). None of those exists — there is no lead score
 * anywhere in EvalOS or the mirror, and no request behind a deal. They are left out rather than faked, because a screen
 * showing a verdict nobody reached is worse than a screen missing a column. Each omission is noted
 * where the column would have been.
 *
 * <p><strong>No status colour anywhere on this screen, and that is the rule rather than an
 * oversight.</strong> `ui-context.md`: red / amber / green are reserved for status — deadlines,
 * SLA, capacity — and never for decoration. A won deal drawn green and a lost one drawn red is
 * exactly the collision that rule exists to stop, because the reader then has to know which
 * colours mean "this went well" and which mean "act on this". Outcome is a word here, not a hue.
 */
export default function DealPage() {
  const { opportunityId = '' } = useParams()
  const role = useMe().role
  const [editing, setEditing] = useState(false)
  const navigate = useNavigate()

  const { data: contact, state: contactState } = useMetrics<DealContact | null>(
    (signal) => fetchDealContact(opportunityId, signal),
    [opportunityId],
  )
  // The board is read for its stage list, which the actions need to offer a move. Same payload the
  // board itself draws from, so the two cannot disagree about which stages are "mine".
  const { data: board } = useMetrics((signal) => fetchOpportunityBoard(signal), [opportunityId])
  const stages = (board?.columns ?? []).map((c) => ({ stageId: c.stageId, stageName: c.stageName }))
  const column = board?.columns.find((c) => c.deals.some((d) => d.opportunityId === opportunityId))
  const deal = column?.deals.find((d) => d.opportunityId === opportunityId)

  const name = deal?.name ?? contact?.name ?? 'Opportunity'
  // Unit 63: the ENM's deals are hiring candidates on their brand's hiring pipeline.
  const hiring = role === 'EXPERT_NETWORK_MANAGER'

  return (
    <section className="space-y-4">
      <Link
        to={hiring ? '/hiring' : '/opportunities/board'}
        className="inline-flex items-center gap-1.5 text-sm font-medium"
        style={{ color: 'var(--accent-primary)' }}
      >
        <ArrowLeft className="h-4 w-4" aria-hidden />
        {hiring ? 'Hiring pipeline' : 'My pipeline'}
      </Link>

      {/* `items-start` so the sidebar does not stretch to the left column's height: the two are
          independent stacks, not a grid of equal cells. */}
      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_20rem]">

        <div className="min-w-0 space-y-4">

          <Surface>
            <div className="flex flex-wrap items-start gap-4">
              <Initials name={name} />

              <div className="min-w-0 flex-1 space-y-2">
                <h1 className="text-xl font-semibold tracking-tight">{name}</h1>

                <div className="flex flex-wrap items-center gap-1.5">
                  {column && <span className="chip chip-accent">{column.stageName}</span>}
                  {/* Money only when there is money: a deal with no value shows no chip rather
                      than `$0`, which is a figure somebody would act on.

                      There is no "Hot" chip beside it. The design had one; nothing in EvalOS or
                      the GHL mirror scores a lead, so it would be a badge with no input. */}
                  {deal?.amount !== null && deal?.amount !== undefined && (
                    <span className="chip font-num">{formatMoney(deal.amount)}</span>
                  )}
                  {/* Uppercased for the same reason the board does it: `won` is GHL's own
                      vocabulary and reads as a label rather than as a sentence. `open` is the
                      resting state and says nothing worth a chip. */}
                  {deal && deal.status !== 'open' && (
                    <span className="chip uppercase">{deal.status}</span>
                  )}
                  <span className="chip">{age(deal?.updatedAt ?? null)}</span>
                </div>

                <p
                  className="flex items-center gap-1.5 text-sm"
                  style={{ color: 'var(--text-muted)' }}
                >
                  <Signpost className="h-3.5 w-3.5 shrink-0" aria-hidden />
                  In GoHighLevel. EvalOS shows it; GHL owns it.
                </p>
              </div>

              {/* Only the desk that owns the deal may edit it, so only they are offered the
                  control — the server refuses anyone else, and a button that always 403s is a
                  worse answer than no button. */}
              {role === 'SALES' && deal && (
                <button type="button" className="btn" onClick={() => setEditing(true)}>
                  <Pencil className="h-3.5 w-3.5" aria-hidden />
                  Edit or delete
                </button>
              )}
            </div>

            {/* Who the deal is with, on the record itself (2026-09-30): a salesperson opening a
                deal is usually about to mail or ring somebody. The deal's own facts are the card
                below. Name only when it differs from the heading, which is usually the same person. */}
            <dl
              className="mt-5 grid gap-4 border-t pt-4 sm:grid-cols-3"
              style={{ borderColor: 'var(--border-default)' }}
            >
              {contact?.name && contact.name !== name && (
                <Field icon={<User />} label="Contact" value={contact.name} />
              )}
              <Field icon={<Mail />} label="Email" value={contact?.email ?? null} href={mailto(contact?.email)} />
              <Field icon={<Phone />} label="Phone" value={contact?.phone ?? null} href={tel(contact?.phone)} />
              <Field icon={<Building2 />} label="Company" value={contact?.company ?? null} />
            </dl>
          </Surface>

          {/* Everything else GHL holds (2026-09-30): the deal's custom fields, source, assignee and
              when it opened, then the contact's country, tags and custom fields. Only the three you
              act on — email, phone, company — are in the header. This replaced a sidebar
              "Contact details" card that split one record across two places.
              No "Last activity" row: the header's "Updated Xd ago" chip is the same fact. */}
          <Panel title="Opportunity details" icon={<ClipboardList />}>
            {contactState.kind === 'error' ? (
              <p className="text-sm" style={{ color: 'var(--status-red)' }}>
                {contactState.note}
              </p>
            ) : (
              <dl className="grid gap-4 sm:grid-cols-2">
                {contact?.dealFields.map((field) => (
                  <Field key={field.label} icon={<Info />} label={field.label} value={field.value} />
                ))}
                <Field icon={<Signpost />} label="Source" value={sourceLabel(contact?.source ?? null)} />
                <Field icon={<UserCheck />} label="Assigned to" value={contact?.assignedTo ?? null} />
                <Field icon={<CalendarDays />} label="Created on" value={date(contact?.createdAt ?? null)} />
                <Field icon={<Globe />} label="Country" value={contact?.country ?? null} />
                <Field icon={<Tag />} label="Tags" value={contact?.tags.length ? contact.tags.join(', ') : null} />
                {contact?.contactFields.map((field) => (
                  <Field key={field.label} icon={<Info />} label={field.label} value={field.value} />
                ))}
              </dl>
            )}
          </Panel>
        </div>

        <div className="space-y-4 xl:sticky xl:top-4">
          {hiring && deal && (
            <Panel title="Candidate" icon={<Zap />}>
              <HiringActions
                opportunityId={opportunityId}
                stages={stages}
                candidate={{ name, email: contact?.email ?? null, phone: contact?.phone ?? null }}
                onChanged={() => window.location.reload()}
              />
            </Panel>
          )}

          {role === 'SALES' && deal && (
            <Panel title="Actions" icon={<Zap />}>
              <DealActions
                opportunityId={opportunityId}
                contactId={deal.contactId}
                stages={stages}
                onChanged={() => window.location.reload()}
              />
            </Panel>
          )}

          <Panel title="Notes" icon={<StickyNote />}>
            <DealNotes opportunityId={opportunityId} />
          </Panel>
        </div>
      </div>

      {/* Not while the contact read is in flight: the dialog seeds owner and custom fields from it. */}
      {editing && deal && contactState.kind !== 'loading' && (
        <DealEditDialog
          opportunityId={opportunityId}
          name={deal.name}
          amount={deal.amount}
          stageId={column?.stageId ?? null}
          stages={stages}
          assignedToId={contact?.assignedToId ?? null}
          fieldValues={contact?.dealFieldValues ?? {}}
          onClose={() => setEditing(false)}
          onSaved={() => window.location.reload()}
          onDeleted={() => navigate('/opportunities/board')}
        />
      )}
    </section>
  )
}

/**
 * The person's initials, as the one piece of identity on the screen that is not text.
 *
 * Accent-soft rather than a generated per-person colour: a colour derived from a name is
 * categorical colour by the back door, which `ui-context.md` settled against on the GM dashboard.
 */
function Initials({ name }: { name: string }) {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('')

  return (
    <div
      className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg text-base font-semibold"
      style={{ background: 'var(--accent-soft)', color: 'var(--accent-primary)' }}
      aria-hidden
    >
      {letters || '—'}
    </div>
  )
}

/** A labelled fact: icon, then label above value. The header's contact strip and Opportunity details. */
function Field({
  icon,
  label,
  value,
  href,
}: {
  icon: ReactNode
  label: string
  value: string | null
  href?: string
}) {
  return (
    <div className="flex min-w-0 gap-2.5">
      <span
        className="mt-0.5 shrink-0 [&>svg]:h-4 [&>svg]:w-4"
        style={{ color: 'var(--text-muted)' }}
        aria-hidden
      >
        {icon}
      </span>
      <div className="min-w-0">
        <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {label}
        </dt>
        <dd className="text-sm break-words">{renderValue(value, href)}</dd>
      </div>
    </div>
  )
}

/**
 * An em dash rather than an empty cell: "we do not hold this" is a fact, and a blank line reads as
 * a rendering bug. A value we DO hold and can act on is a link — the accent is carrying "you can
 * click this", which is what an accent is for.
 */
function renderValue(value: string | null, href?: string): ReactNode {
  if (!value?.trim()) return '—'
  if (!href) return value
  return (
    <a href={href} className="hover:underline" style={{ color: 'var(--accent-primary)' }}>
      {value}
    </a>
  )
}

function mailto(email: string | null | undefined): string | undefined {
  return email?.trim() ? `mailto:${email.trim()}` : undefined
}

function tel(phone: string | null | undefined): string | undefined {
  // Spaces and punctuation are legal in a `tel:` URI but several dialers mis-parse them.
  return phone?.trim() ? `tel:${phone.replace(/[^\d+]/g, '')}` : undefined
}

function date(iso: string | null): string | null {
  return iso === null ? null : new Date(iso).toLocaleDateString()
}

/**
 * How long since GHL last saw this deal move.
 *
 * Words rather than a date, because the question a salesperson is asking of this chip is "have I
 * left this too long", not "what was the date". A null stamp says so outright — `dealAge.ts` is
 * explicit that a missing date must not be rendered as fresh.
 */
function age(updatedAt: string | null): string {
  if (updatedAt === null) return 'Age unknown'
  const days = daysSince(updatedAt)
  if (days <= 0) return 'Updated today'
  return `Updated ${days}d ago`
}
