import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ArrowLeft,
  Building2,
  CalendarDays,
  Clock,
  Mail,
  Pencil,
  Phone,
  Signpost,
  StickyNote,
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
import DealApplication from './DealApplication'
import DealDocuments from './DealDocuments'
import DealEditDialog from './DealEditDialog'
import DealNotes from './DealNotes'
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
 * two other cards — readable for a note, useless for a questionnaire.
 *
 * <p><strong>Two columns as of 2026-09-23.</strong> The left is the record — who they are, what
 * they submitted, what they sent, what has been said about them — read top to bottom. The right is
 * what you <em>do</em> and what you <em>check</em>, and it stays in view while the left scrolls.
 * That split is what stopped the actions being the last thing on a long page: a salesperson
 * opening a deal to move it a stage had to scroll past a whole questionnaire to reach the control.
 *
 * <p><strong>Everything here is a field something actually stores.</strong> The design this
 * follows also carried a "Hot" lead-temperature badge, a second questionnaire, and a
 * Verified / Pending-review column on the documents. None of those exists — there is no lead score
 * anywhere in EvalOS or the mirror, there is one {@code client_application} per opportunity, and
 * nothing reviews a request document. They are left out rather than faked, because a screen
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

  return (
    <section className="space-y-4">
      <Link
        to="/opportunities/board"
        className="inline-flex items-center gap-1.5 text-sm font-medium"
        style={{ color: 'var(--accent-primary)' }}
      >
        <ArrowLeft className="h-4 w-4" aria-hidden />
        My pipeline
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
                  Edit
                </button>
              )}
            </div>

            {/* The three things you reach for first, on the record itself rather than only in the
                sidebar: a salesperson opening a deal is usually about to mail or ring somebody. */}
            <dl
              className="mt-5 grid gap-4 border-t pt-4 sm:grid-cols-3"
              style={{ borderColor: 'var(--border-default)' }}
            >
              <Field icon={<Mail />} label="Email" value={contact?.email ?? null} href={mailto(contact?.email)} />
              <Field icon={<Phone />} label="Phone" value={contact?.phone ?? null} href={tel(contact?.phone)} />
              <Field icon={<Building2 />} label="Company" value={contact?.company ?? null} />
            </dl>
          </Surface>

          {/* Each of these renders its own panel, or nothing at all — the wrapper cannot live
              here. Both are empty for a deal that did not come through the portal, which is most
              of the board, and a titled empty box on every phoned-in deal is noise that also
              looks like a panel that failed to load. */}
          <DealApplication opportunityId={opportunityId} />

          <DealDocuments opportunityId={opportunityId} />

          <Panel title="Notes" icon={<StickyNote />}>
            <DealNotes opportunityId={opportunityId} />
          </Panel>
        </div>

        <div className="space-y-4 xl:sticky xl:top-4">
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

          <Panel title="Contact details" icon={<User />}>
            {contactState.kind === 'error' ? (
              <p className="text-sm" style={{ color: 'var(--status-red)' }}>
                {contactState.note}
              </p>
            ) : (
              <dl className="space-y-3">
                <Row icon={<User />} label="Name" value={contact?.name ?? null} />
                <Row icon={<Mail />} label="Email" value={contact?.email ?? null} href={mailto(contact?.email)} />
                <Row icon={<Phone />} label="Phone" value={contact?.phone ?? null} />
                <Row icon={<Building2 />} label="Company" value={contact?.company ?? null} />
                <Row icon={<Signpost />} label="Source" value={sourceLabel(contact?.source ?? null)} />
                <Row icon={<UserCheck />} label="Assigned to" value={contact?.assignedTo ?? null} />
                <Row icon={<CalendarDays />} label="Created on" value={date(contact?.createdAt ?? null)} />
                <Row icon={<Clock />} label="Last activity" value={age(deal?.updatedAt ?? null)} />
              </dl>
            )}
          </Panel>
        </div>
      </div>

      {editing && deal && (
        <DealEditDialog
          opportunityId={opportunityId}
          name={deal.name}
          amount={deal.amount}
          onClose={() => setEditing(false)}
          onSaved={() => window.location.reload()}
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

/** A labelled fact in the header strip: icon, then label above value. */
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
        <dd className="truncate text-sm">{renderValue(value, href)}</dd>
      </div>
    </div>
  )
}

/** A labelled fact in the sidebar: icon, label and value on one line. */
function Row({
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
    <div className="flex items-baseline gap-2.5 text-sm">
      <span
        className="shrink-0 self-start pt-0.5 [&>svg]:h-3.5 [&>svg]:w-3.5"
        style={{ color: 'var(--text-muted)' }}
        aria-hidden
      >
        {icon}
      </span>
      <dt className="w-24 shrink-0 text-xs" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd className="min-w-0 flex-1 truncate text-right">{renderValue(value, href)}</dd>
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
