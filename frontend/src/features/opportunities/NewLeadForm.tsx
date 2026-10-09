import { useState } from 'react'
import { openLead, type Lead } from './opportunityApi'

/**
 * Opening a lead, without opening GHL.
 *
 * **The form asks for an email or a phone and will not submit without one.** That is not a
 * validation preference: GHL matches an existing contact on email, then phone, and a lead with
 * neither gives it nothing to match on — so the upsert that protects against double-submission
 * silently stops protecting anything and every save creates another contact. The server refuses
 * it too; asking here is what stops the marketer discovering it as an error.
 *
 * **No pipeline field.** The server takes it from the caller's token. A pipeline the form could
 * name would make the whole access model advisory.
 */
export default function NewLeadForm({ onOpened, candidate = false }: { onOpened: () => void; candidate?: boolean }) {
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<Lead | null>(null)

  const reachable = email.trim() !== '' || phone.trim() !== ''

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    // Guarding on `busy` as well as disabling the button: a double-submit is exactly what upsert
    // exists to survive, and not sending the second request is better than surviving it.
    if (busy || !reachable) return
    setBusy(true)
    setError(null)
    try {
      const lead = await openLead({
        firstName: firstName.trim() || undefined,
        lastName: lastName.trim() || undefined,
        email: email.trim() || undefined,
        phone: phone.trim() || undefined,
        monetaryValue: value.trim() === '' ? undefined : Number(value),
      })
      setResult(lead)
      setFirstName('')
      setLastName('')
      setEmail('')
      setPhone('')
      setValue('')
      onOpened()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not open the lead')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3">
      <fieldset className="grid gap-3">
        <legend className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          {candidate ? 'Candidate' : 'Contact'}
        </legend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="First name" value={firstName} onChange={setFirstName} />
        <Field label="Last name" value={lastName} onChange={setLastName} />
        <Field label="Email" value={email} onChange={setEmail} type="email" />
        <Field label="Phone" value={phone} onChange={setPhone} />
        {/* A candidate has no deal value (Unit 63). */}
        {!candidate && <Field label="Valuation" value={value} onChange={setValue} type="number" />}
        </div>
      </fieldset>

      {!reachable && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          An email or a phone number is needed — GHL matches an existing contact on those, so a
          lead with neither would be created again every time it is saved.
        </p>
      )}
      {error && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }} role="alert">
          {error}
        </p>
      )}
      {result && (
        <p className="text-sm" style={{ color: 'var(--status-green)' }}>
          {/*
            "Opened" and "already open" are different outcomes and the marketer should be told
            which. GHL's upsert reports it; guessing would mean two people quietly working one
            deal each thinking they created it.
          */}
          {result.created ? 'Lead opened.' : 'That contact already had an open deal in your pipeline.'}
        </p>
      )}

      <button
        type="submit"
        disabled={busy || !reachable}
        className="justify-self-start rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
        style={{ background: 'var(--accent-primary)', color: '#fff' }}
      >
        {busy ? 'Opening…' : candidate ? 'Add candidate' : 'Open lead'}
      </button>
    </form>
  )
}

function Field({
  label,
  value,
  onChange,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (next: string) => void
  type?: string
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <input
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="rounded-lg border px-2 py-1.5"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
      />
    </label>
  )
}
