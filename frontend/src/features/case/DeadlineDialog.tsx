import { useState, type ReactElement } from 'react'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { changeDeadline, postNote } from './caseApi'
import { endOfDayIso, toDateInput } from './caseRules'

/**
 * The date promised to the client (GM, PM; Unit 66). `DeadlineRequest` is `{deadline}` only, so the
 * optional reason becomes a timeline note written after the save.
 */
export default function DeadlineDialog({
  caseId,
  deadline,
  trigger,
  onSaved,
}: {
  caseId: string
  deadline: string | null
  trigger: ReactElement
  onSaved: () => void
}) {
  const [open, setOpen] = useState(false)
  const [date, setDate] = useState(toDateInput(deadline))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function save(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await changeDeadline(caseId, endOfDayIso(date))
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : 'The deadline was not changed')
      return
    }
    // The date stands even if the note fails; the reason is worth a retry but not a rollback.
    if (reason.trim()) await postNote(caseId, `Deadline changed to ${date}: ${reason.trim()}`).catch(() => undefined)
    setOpen(false)
    setReason('')
    onSaved()
  }

  return (
    <DialogRoot
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) {
          setDate(toDateInput(deadline))
          setError(null)
        }
      }}
    >
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent title="Change deadline" description="The date promised to the client.">
        <form onSubmit={save} className="flex flex-col gap-3 text-sm">
          <input
            type="date"
            required
            aria-label="Deadline"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className="rounded-md border p-2"
            style={{ borderColor: 'var(--border-default)' }}
          />
          <label className="flex flex-col gap-1">
            <span style={{ color: 'var(--text-muted)' }}>Reason (optional, goes to the timeline)</span>
            <textarea
              rows={2}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="rounded-md border p-2"
              style={{ borderColor: 'var(--border-default)' }}
            />
          </label>
          {error && (
            <p role="alert" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}
          <div className="flex justify-end">
            <button
              type="submit"
              className="rounded-md px-3 py-1.5 font-medium"
              style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
            >
              Save
            </button>
          </div>
        </form>
      </DialogContent>
    </DialogRoot>
  )
}
