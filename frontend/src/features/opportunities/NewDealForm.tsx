import { useState } from 'react'
import { SheetContent, SheetRoot } from '../../components/ui/dialog'
import { useMetrics } from '../dashboards/useMetrics'
import {
  createDeal,
  openLead,
  fetchGhlUsers,
  fetchOpportunityFields,
  type GhlUser,
  type BoardColumn,
  type OpportunityField,
} from './opportunityApi'

/**
 * The custom fields a salesperson fills, and the ones they must not.
 *
 * <p>The location defines twenty. They split cleanly in two, and the split is the decision:
 *
 * <ul>
 * <li><strong>Intake</strong> — what the client is asking for, known at the point of sale.
 *     Service Requested, Visa Category, turnaround, language, page count, and the client's own
 *     description. These are the facts `GhlOpportunityHandler` currently says "are a PM's to fill
 *     in", which is the re-interview this form exists to prevent.</li>
 * <li><strong>Production state</strong> — Assigned Expert, Draft Link, SLA Status, Docs Received
 *     Date, Actual Won Date. GHL holds these as a shadow copy of facts <em>EvalOS owns</em>: the
 *     expert is `evalos_case.expert_id`, the draft is `case_document`, the SLA is
 *     `SlaCalculator`. A salesperson typing into them would create a second answer that goes
 *     stale the moment production moves, so they are not offered here.</li>
 * </ul>
 *
 * <p>Matched on `fieldKey` rather than on id or label: the id is location-scoped and would break
 * on the next sub-account swap, and a label is renameable in the GHL UI. Anything the location
 * adds that is not on this list simply does not appear — the form shows what sales is asked to
 * know, not everything the field set happens to contain.
 */
export const INTAKE_FIELD_KEYS: readonly string[] = [
  'opportunity.service_requested',
  'opportunity.visa_category_if_applicable',
  'opportunity.service_turn_around_time',
  'opportunity.translation_turn_around_time',
  'opportunity.document_original_language',
  'opportunity.how_many_pages',
  'opportunity.lead_source',
  'opportunity.lead_typeopportunity',
  'opportunity.requirement',
  'opportunity.tell_us_about_your_case',
]

/**
 * The custom fields a candidate is asked, in the order they are asked: the "Join as evaluator" website
 * fields first, then how they came to us. Matched on name, lower-cased with spacing collapsed, because
 * GHL gives these no key we know. Every other opportunity field is about a client's case and is left
 * off; a field added in GHL later does not appear here until it is added to this list.
 */
const CANDIDATE_FIELDS = [
  'current title (website) (join as evaluator)',
  'primary field of expertise(website) (join as evaluator)',
  'category applying (c)(website evaluator)',
  'lead source',
  'how did you hear about us? (opportunity)',
  'how did you hear about us? (website)',
  'message',
]

const fieldName = (field: OpportunityField) => field.name.trim().replace(/\s+/g, ' ').toLowerCase()

/**
 * "Add opportunity" for a salesperson — the fields GHL's own form asks for, and nothing EvalOS
 * would have to invent.
 *
 * <p><strong>Sales could not open a deal at all before this.</strong> Unit 40 shipped a desk where
 * every route was a `PUT` on an existing opportunity, because Marketing opened leads and GHL's
 * automation promoted them. That left no door for the two cases the business actually has: an
 * inbound call from someone nobody has logged, and a repeat client buying a second service.
 *
 * <p><strong>The second of those is why this is a create and not an upsert, and why it can ask a
 * question.</strong> Unit 39 §3a chose upsert for the marketing form and named the consequence:
 * upsert keys on (contact, pipeline), so using it here would overwrite a repeat client's first
 * deal instead of opening their second. Taking the escape hatch loses upsert's accidental
 * duplicate protection, so the server answers 409 `DEAL_ALREADY_OPEN` and this form turns that
 * into a confirmation rather than an error — the salesperson sees the deal that already exists
 * and decides.
 *
 * <p><strong>Contact fields, not a contact picker.</strong> GHL matches an existing contact on
 * email then phone, so typing the client's email either finds the person the business already
 * knows or creates them — one field doing the work of a search box and a create form. It is also
 * why email-or-phone is required and the server refuses without one: with neither, every save
 * would mint another contact.
 */
export default function NewDealForm({
  columns,
  onCreated,
}: {
  columns: readonly BoardColumn[]
  onCreated: () => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-lg px-3 py-1.5 text-sm font-medium"
        style={{ background: 'var(--accent-primary)', color: '#fff' }}
      >
        Add opportunity
      </button>

      <SheetRoot open={open} onOpenChange={setOpen}>
        <SheetContent
          title="Add opportunity"
          description="Opens the deal in GHL on your own pipeline."
        >
          <NewDealFields
            columns={columns}
            onCreated={() => {
              setOpen(false)
              onCreated()
            }}
          />
        </SheetContent>
      </SheetRoot>
    </>
  )
}

/**
 * The form itself, separate from the button that used to be the only way in.
 *
 * <p>Exported because the sidebar is now the trigger (2026-09-17): `NewDealPage` renders these
 * fields directly, so the sheet's own button is not a second entry point competing with the nav.
 *
 * <p>`lead` (Unit 39b) is the BDE's "Add lead": the same GHL opportunity form, sent as the marketing
 * upsert on (contact, pipeline). So the name is optional (GHL gets the contact's) and there is no
 * second-deal question — a repeat enquiry updates the open lead, which is what upsert means.
 */
export function NewDealFields({
  columns,
  onCreated,
  lead = false,
  candidate = false,
}: {
  columns: readonly BoardColumn[]
  onCreated: () => void
  lead?: boolean
  /**
   * Spec 83: the ENM's "Add candidate" — the same GHL opportunity form on the hiring pipeline. A
   * candidate has no deal value, and is asked the location's other opportunity fields instead of
   * the client intake ones.
   */
  candidate?: boolean
}) {
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [value, setValue] = useState('')
  // Preset by the board's column "+" (`?stage=`); any stage not on the caller's pipeline just isn't offered.
  const [stageId, setStageId] = useState(() => new URLSearchParams(window.location.search).get('stage') ?? '')
  // Only the pipeline's own stages are offered: a blank choice is replaced by its first stage, which is
  // what GHL would have picked, so the form always names the stage the deal will land in.
  const stages = [...columns].sort((a, b) => a.position - b.position)
  const chosenStage = stages.some((column) => column.stageId === stageId) ? stageId : (stages[0]?.stageId ?? '')
  const [closeDate, setCloseDate] = useState('')
  const [owner, setOwner] = useState('')
  // GHL's own users, as the booking dialog lists them. A failed read leaves only "Unassigned".
  const { data: users } = useMetrics<readonly GhlUser[]>((signal) => fetchGhlUsers(signal), [])

  /**
   * GHL's own field definitions. A failure here is not fatal: the standard fields still submit,
   * and a form that refused to open because a custom-field read timed out would block the deal
   * over metadata.
   */
  const { data: fields } = useMetrics<readonly OpportunityField[]>(
    (signal) => fetchOpportunityFields(signal),
    [],
  )
  const intake = candidate
    ? (fields ?? [])
        .filter((field) => CANDIDATE_FIELDS.includes(fieldName(field)))
        .sort((x, y) => CANDIDATE_FIELDS.indexOf(fieldName(x)) - CANDIDATE_FIELDS.indexOf(fieldName(y)))
    : (fields ?? [])
        .filter((field) => INTAKE_FIELD_KEYS.includes(field.fieldKey))
        .sort(
          (a, b) => INTAKE_FIELD_KEYS.indexOf(a.fieldKey) - INTAKE_FIELD_KEYS.indexOf(b.fieldKey),
        )
  const [custom, setCustom] = useState<Record<string, string>>({})

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /**
   * Set when the server refused with `DEAL_ALREADY_OPEN`. Holding it in state rather than
   * re-submitting immediately is the point: the second press is the confirmation, and it has to
   * be the user's.
   */
  const [duplicate, setDuplicate] = useState<string | null>(null)

  const hasContact = email.trim() !== '' || phone.trim() !== ''
  const ready = (lead || name.trim() !== '') && hasContact

  async function submit(event: React.FormEvent, confirmSecondDeal: boolean) {
    event.preventDefault()
    if (!ready) return
    setBusy(true)
    setError(null)
    const common = {
      firstName: firstName.trim() || undefined,
      lastName: lastName.trim() || undefined,
      email: email.trim() || undefined,
      phone: phone.trim() || undefined,
      monetaryValue: value.trim() === '' ? undefined : Number(value),
      stageId: chosenStage || undefined,
      expectedCloseDate: closeDate || undefined,
      customFields: custom,
      assignedTo: owner || undefined,
    }
    try {
      if (lead) await openLead({ ...common, name: name.trim() || undefined })
      else await createDeal({ ...common, name: name.trim(), confirmSecondDeal })
      onCreated()
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'The deal could not be opened.'
      // The server's own words carry the existing deal's id, so the confirmation can name it.
      if (message.includes('already has an open deal')) setDuplicate(message)
      else setError(message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => submit(e, false)} className="grid gap-3">
      <fieldset className="grid gap-3">
        <legend className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          {candidate ? 'Candidate' : 'Client'}
        </legend>
        <div className="grid grid-cols-2 gap-3">
          <Field label="First name" value={firstName} onChange={setFirstName} />
          <Field label="Last name" value={lastName} onChange={setLastName} />
        </div>
        <Field label="Email" value={email} onChange={setEmail} type="email" />
        <Field label="Phone" value={phone} onChange={setPhone} />
        {!hasContact && (
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            An email or a phone number is required — it is how an existing client is recognised
            instead of being added twice.
          </p>
        )}
      </fieldset>

      <fieldset className="grid gap-3">
        <legend className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Opportunity
        </legend>
        <Field label={lead ? 'Name (defaults to the contact)' : 'Name'} value={name} onChange={setName} />
        <div className="grid grid-cols-2 gap-3">
          {/* A candidate has no deal value (Unit 63). */}
          {!candidate && <Field label="Value" value={value} onChange={setValue} type="number" />}
          <Field label="Expected close" value={closeDate} onChange={setCloseDate} type="date" />
        </div>
        <label className="grid gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Stage</span>
          <select
            value={chosenStage}
            onChange={(e) => setStageId(e.target.value)}
            className="rounded-lg border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          >
            {stages.map((column) => (
              <option key={column.stageId} value={column.stageId}>
                {column.stageName}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Owner</span>
          <select
            value={owner}
            onChange={(e) => setOwner(e.target.value)}
            className="rounded-lg border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          >
            {/* Blank = GHL's own round-robin, which is what happened before there was a picker. */}
            <option value="">Unassigned (GHL assigns)</option>
            {(users ?? []).map((user) => (
              <option key={user.id} value={user.id}>
                {user.name}
              </option>
            ))}
          </select>
        </label>
      </fieldset>

      {intake.length > 0 && (
        <fieldset className="grid gap-3">
          <legend
            className="text-xs font-medium uppercase tracking-wide"
            style={{ color: 'var(--text-muted)' }}
          >
            {candidate ? 'Details' : 'What they need'}
          </legend>
          {intake.map((field) => (
            <CustomFieldInput
              key={field.id}
              field={field}
              value={custom[field.id] ?? ''}
              onChange={(next) => setCustom((prev) => ({ ...prev, [field.id]: next }))}
            />
          ))}
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {candidate
              ? "Read from GHL's opportunity fields."
              : 'These travel with the deal into production, so nobody has to ask the client twice.'}
          </p>
        </fieldset>
      )}

      {duplicate && (
        <div
          className="rounded-lg border p-3 text-sm"
          style={{
            background: 'var(--status-amber-bg)',
            borderColor: 'var(--status-amber)',
            color: 'var(--status-amber)',
          }}
          role="alert"
        >
          <p>{duplicate}</p>
          <p className="mt-1 text-xs">
            A repeat client buying a second service is normal — but check the first deal before
            opening another, because two open deals for one client are hard to tell apart later.
          </p>
          <button
            type="button"
            disabled={busy}
            onClick={(e) => submit(e, true)}
            className="mt-2 rounded-lg border px-2 py-1 text-xs font-medium"
            style={{ borderColor: 'var(--status-amber)' }}
          >
            {busy ? 'Opening…' : 'Open a second deal anyway'}
          </button>
        </div>
      )}

      {error && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }} role="alert">
          {error}
        </p>
      )}

      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {candidate
          ? 'Opens on the expert hiring pipeline in GHL. A person who already has an open candidate there is updated, not duplicated.'
          : lead
          ? 'Opens on your own pipeline in GHL. A contact who already has an open lead there is updated, not duplicated.'
          : 'Opens on your own pipeline, as an open deal. Winning it is a separate step — that is what creates the case.'}
      </p>

      <button
        type="submit"
        disabled={!ready || busy || duplicate !== null}
        className="rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
        style={{ background: 'var(--accent-primary)', color: '#fff' }}
      >
        {busy ? 'Opening…' : candidate ? 'Add candidate' : lead ? 'Open lead' : 'Open deal'}
      </button>
    </form>
  )
}

/**
 * One GHL custom field, rendered by its own `dataType`.
 *
 * <p>An unknown type falls through to a text input rather than being skipped: GHL can add a type,
 * and silently dropping a field the location asked for is worse than rendering it plainly.
 */
export function CustomFieldInput({
  field,
  value,
  onChange,
}: {
  field: OpportunityField
  value: string
  onChange: (value: string) => void
}) {
  if (field.dataType === 'SINGLE_OPTIONS') {
    return (
      <label className="grid gap-1 text-sm">
        <span style={{ color: 'var(--text-muted)' }}>{field.name}</span>
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="rounded-lg border px-2 py-1.5"
          style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
        >
          <option value="">Not specified</option>
          {field.picklistOptions.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </label>
    )
  }

  if (field.dataType === 'LARGE_TEXT') {
    return (
      <label className="grid gap-1 text-sm">
        <span style={{ color: 'var(--text-muted)' }}>{field.name}</span>
        <textarea
          rows={3}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="rounded-lg border px-2 py-1.5"
          style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
        />
      </label>
    )
  }

  const type =
    field.dataType === 'NUMERICAL' ? 'number' : field.dataType === 'DATE' ? 'date' : 'text'
  return <Field label={field.name} value={value} onChange={onChange} type={type} />
}

function Field({
  label,
  value,
  onChange,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (value: string) => void
  type?: string
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border px-2 py-1.5"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
      />
    </label>
  )
}
