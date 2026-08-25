import { useState } from 'react'
import { DialogContent, DialogRoot } from '../../components/ui/dialog'
import { api, unwrap } from '../../lib/api'

/**
 * The Case Manager's escalation, wired to `POST /cases/{id}/flag`.
 *
 * The reason is required by the server and by this form. A flag with no reason asks the PM to
 * guess what is wrong, which is the whole thing it was meant to save them.
 *
 * Controlled from the caller so the trigger can live inline in a row rather than being a button
 * this component owns — the same flag is raised from more than one place.
 */
export default function FlagToPmDialog({
  caseId,
  caseCode,
  open,
  onOpenChange,
  onFlagged,
}: {
  caseId: string
  caseCode: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onFlagged: () => void
}) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      await unwrap(api.post(`/cases/${caseId}/flag`, { reason }))
      setReason('')
      onOpenChange(false)
      // Refresh rather than patch local state: the flag writes an audit row and notifies the PM,
      // and the next read is the only thing that can confirm the server agreed.
      onFlagged()
    } catch (cause) {
      setError((cause as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <DialogRoot open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title={`Flag ${caseCode} to your PM`}
        description="This notifies the case's Project Manager and records the reason on the timeline. The case does not move."
        footer={
          <button
            type="button"
            disabled={busy || reason.trim() === ''}
            onClick={submit}
            className="h-9 px-3 text-sm font-medium disabled:opacity-50"
            style={{
              background: 'var(--accent-primary)',
              color: '#fff',
              borderRadius: 'var(--radius-md)',
            }}
          >
            {busy ? 'Flagging…' : 'Flag to PM'}
          </button>
        }
      >
        <label className="block text-sm">
          <span className="font-medium">What is blocking this case?</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={4}
            className="mt-1 w-full p-2 text-sm"
            style={{
              border: '1px solid var(--border-default)',
              borderRadius: 'var(--radius-md)',
              background: 'var(--bg-base)',
            }}
          />
        </label>
        {error && (
          <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
            {error}
          </p>
        )}
      </DialogContent>
    </DialogRoot>
  )
}
