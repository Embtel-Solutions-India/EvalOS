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
export default function NewLeadForm({ onOpened }: { onOpened: () => void }) {
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
    <form onSubmit={submit} className="space-y-3 rounded border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900">New lead</h2>

      <div className="grid grid-cols-2 gap-2">
        <Field label="First name" value={firstName} onChange={setFirstName} />
        <Field label="Last name" value={lastName} onChange={setLastName} />
        <Field label="Email" value={email} onChange={setEmail} type="email" />
        <Field label="Phone" value={phone} onChange={setPhone} />
        <Field label="Valuation" value={value} onChange={setValue} type="number" />
      </div>

      {!reachable && (
        <p className="text-xs text-slate-500">
          An email or a phone number is needed — GHL matches an existing contact on those, so a
          lead with neither would be created again every time it is saved.
        </p>
      )}
      {error && <p className="text-xs text-rose-600">{error}</p>}
      {result && (
        <p className="text-xs text-emerald-700">
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
        className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40"
      >
        {busy ? 'Opening…' : 'Open lead'}
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
    <label className="block text-xs text-slate-600">
      {label}
      <input
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-0.5 w-full rounded border border-slate-300 px-2 py-1 text-sm text-slate-900"
      />
    </label>
  )
}
