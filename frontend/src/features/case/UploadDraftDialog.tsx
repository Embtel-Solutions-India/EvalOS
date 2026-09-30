import { useState, type ReactElement } from 'react'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { postNote, uploadDraft } from './caseApi'
import { submitDraft } from './draftRules'

/**
 * The next draft version as Word + PDF, plus an optional note to the PM (Unit 66). One component for
 * every place a CM starts an upload, each passing its own `trigger`. Submitting moves the case to
 * PM review, which is why this is a Dialog and not a Sheet.
 */
export default function UploadDraftDialog({
  caseId,
  nextVersion,
  trigger,
  onUploaded,
}: {
  caseId: string
  nextVersion: number
  trigger: ReactElement
  onUploaded: () => void
}) {
  const [open, setOpen] = useState(false)
  const [docx, setDocx] = useState<File | null>(null)
  const [pdf, setPdf] = useState<File | null>(null)
  const [note, setNote] = useState('')
  const [state, setState] = useState<'idle' | 'sending' | 'failed' | 'note-failed'>('idle')

  function reset() {
    setDocx(null)
    setPdf(null)
    setNote('')
    setState('idle')
  }

  function close() {
    const uploaded = state === 'note-failed'
    setOpen(false)
    reset()
    if (uploaded) onUploaded()
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!docx || !pdf) return
    setState('sending')
    try {
      const result = await submitDraft(
        { upload: () => uploadDraft(caseId, docx, pdf), note: (text) => postNote(caseId, text) },
        note,
      )
      if (result === 'note-failed') {
        setState('note-failed')
        return
      }
      setOpen(false)
      reset()
      onUploaded()
    } catch {
      setState('failed')
    }
  }

  async function retryNote() {
    try {
      await postNote(caseId, note.trim())
    } catch {
      return // still 'note-failed'; the text stays in the box
    }
    setOpen(false)
    reset()
    onUploaded()
  }

  return (
    <DialogRoot open={open} onOpenChange={(next) => (next ? setOpen(true) : close())}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent title={`Upload draft v${nextVersion}`} description="Submits the draft to PM review.">
        {state === 'note-failed' ?
          <div className="flex flex-col gap-3 text-sm">
            <p role="alert" style={{ color: 'var(--status-amber)' }}>
              Draft submitted; the note was not saved.
            </p>
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={3}
              aria-label="Note to PM"
              className="rounded-md border p-2"
              style={{ borderColor: 'var(--border-default)' }}
            />
            <div className="flex justify-end gap-2">
              <button type="button" onClick={close} className="rounded-md px-3 py-1.5" style={{ background: 'var(--bg-raised)' }}>
                Close
              </button>
              <button
                type="button"
                onClick={() => void retryNote()}
                className="rounded-md px-3 py-1.5 font-medium"
                style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
              >
                Save note
              </button>
            </div>
          </div>
        : <form onSubmit={submit} className="flex flex-col gap-3 text-sm">
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>Word file (.docx)</span>
              <input
                type="file"
                accept=".docx"
                required
                onChange={(e) => setDocx(e.target.files?.[0] ?? null)}
                className="field w-full"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>PDF of the same draft</span>
              <input
                type="file"
                accept="application/pdf,.pdf"
                required
                onChange={(e) => setPdf(e.target.files?.[0] ?? null)}
                className="field w-full"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>Note to PM (optional)</span>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                className="field w-full"
              />
            </label>
            {state === 'failed' && (
              <p role="alert" style={{ color: 'var(--status-red)' }}>
                The draft was not uploaded. Check both files are a real Word document and PDF under 15MB, then try
                again.
              </p>
            )}
            <div className="flex justify-end gap-2">
              <button type="button" onClick={close} className="rounded-md px-3 py-1.5" style={{ background: 'var(--bg-raised)' }}>
                Cancel
              </button>
              <button
                type="submit"
                disabled={!docx || !pdf || state === 'sending'}
                className="rounded-md px-3 py-1.5 font-medium disabled:opacity-40"
                style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
              >
                {state === 'sending' ? 'Uploading…' : 'Upload & submit'}
              </button>
            </div>
          </form>
        }
      </DialogContent>
    </DialogRoot>
  )
}
