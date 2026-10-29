import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { openCaseFromGhl } from './caseApi'

/**
 * The PM's New case (2026-10-30, D9 edited): one GHL id, and the server runs the webhook's own intake —
 * the deal must be won in GHL, and the contact, amount and service are read from it. A case already open
 * for that contact and service is opened instead of duplicated.
 */
export default function NewCaseDialog() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [opportunityId, setOpportunityId] = useState('')
  const [contactId, setContactId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const opened = await openCaseFromGhl(opportunityId.trim() || null, contactId.trim() || null)
      setOpen(false)
      navigate(`/cases/${opened.id}`)
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : 'The case was not opened')
    } finally {
      setBusy(false)
    }
  }

  const field = 'rounded-md border p-2 font-mono text-sm'
  const fieldStyle = { borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }

  return (
    <DialogRoot
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) setError(null)
      }}
    >
      <DialogTrigger asChild>
        <button
          type="button"
          className="h-9 rounded-md px-3 text-sm font-medium"
          style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
        >
          New case
        </button>
      </DialogTrigger>
      <DialogContent
        title="New case from GHL"
        description="Paste the won opportunity's ID, or the contact's ID (their latest won deal is used). Client details, amount and service come from GHL, exactly as when GHL opens a case."
      >
        <form onSubmit={submit} className="flex flex-col gap-3 text-sm">
          <label className="flex flex-col gap-1">
            <span style={{ color: 'var(--text-muted)' }}>GHL opportunity ID</span>
            <input value={opportunityId} onChange={(e) => setOpportunityId(e.target.value)} className={field} style={fieldStyle} />
          </label>
          <p className="text-center text-xs" style={{ color: 'var(--text-muted)' }}>or</p>
          <label className="flex flex-col gap-1">
            <span style={{ color: 'var(--text-muted)' }}>GHL contact ID</span>
            <input value={contactId} onChange={(e) => setContactId(e.target.value)} className={field} style={fieldStyle} />
          </label>
          {error && (
            <p role="alert" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}
          <div className="flex justify-end">
            <button
              type="submit"
              disabled={busy || (!opportunityId.trim() && !contactId.trim())}
              className="rounded-md px-3 py-1.5 font-medium disabled:opacity-50"
              style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
            >
              {busy ? 'Opening…' : 'Open case'}
            </button>
          </div>
        </form>
      </DialogContent>
    </DialogRoot>
  )
}
