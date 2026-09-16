import { useState } from 'react'
import { SheetContent, SheetRoot } from '../../components/ui/dialog'
import { useMetrics } from '../dashboards/useMetrics'
import {
  createDeal,
  fetchOpportunityFields,
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
const INTAKE_FIELD_KEYS: readonly string[] = [
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
        style={{ background: 'var(--accent)', color: 'var(--accent-contrast, #fff)' }}
      >
        Add opportunity
      </button>

      <SheetRoot open={open} onOpenChange={setOpen}>
        <SheetContent
          title="Add opportunity"
          description="Opens the deal in GHL on your own pipeline."
        >
          <Fields
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

function Fields({
  columns,
  onCreated,
}: {
  columns: readonly BoardColumn[]
  onCreated: () => void
}) {
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [value, setValue] = useState('')
  const [stageId, setStageId] = useState('')
  const [closeDate, setCloseDate] = useState('')

  /**
   * GHL's own field definitions. A failure here is not fatal: the standard fields still submit,
   * and a form that refused to open because a custom-field read timed out would block the deal
   * over metadata.
   */
  const { data: fields } = useMetrics<readonly OpportunityField[]>(
    (signal) => fetchOpportunityFields(signal),
    [],
  )
  const intake = (fields ?? [])
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
  const ready = name.trim() !== '' && hasContact

  async function submit(event: React.FormEvent, confirmSecondDeal: boolean) {
    event.preventDefault()
    if (!ready) return
    setBusy(true)
    setError(null)
    try {
      await createDeal({
        firstName: firstName.trim() || undefined,
        lastName: lastName.trim() || undefined,
        email: email.trim() || undefined,
        phone: phone.trim() || undefined,
        name: name.trim(),
        monetaryValue: value.trim() === '' ? undefined : Number(value),
        stageId: stageId || undefined,
        expectedCloseDate: closeDate || undefined,
        customFields: custom,
        confirmSecondDeal,
      })
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
          Client
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
        <Field label="Name" value={name} onChange={setName} />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Value" value={value} onChange={setValue} type="number" />
          <Field label="Expected close" value={closeDate} onChange={setCloseDate} type="date" />
        </div>
        <label className="grid gap-1 text-sm">
          <span style={{ color: 'var(--text-muted)' }}>Stage</span>
          <select
            value={stageId}
            onChange={(e) => setStageId(e.target.value)}
            className="rounded-lg border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          >
            {/* GHL puts a new deal in the first stage when none is named, which is nearly always
                what is wanted — so the default says that rather than pre-selecting a stage the
                salesperson did not choose. */}
            <option value="">First stage</option>
            {[...columns]
              .sort((a, b) => a.position - b.position)
              .map((column) => (
                <option key={column.stageId} value={column.stageId}>
                  {column.stageName}
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
            What they need
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
            These travel with the deal into production, so nobody has to ask the client twice.
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
        Opens on your own pipeline, as an open deal. Winning it is a separate step — that is what
        creates the case.
      </p>

      <button
        type="submit"
        disabled={!ready || busy || duplicate !== null}
        className="rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
        style={{ background: 'var(--accent)', color: 'var(--accent-contrast, #fff)' }}
      >
        {busy ? 'Opening…' : 'Open deal'}
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
function CustomFieldInput({
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
