import { useState } from 'react'
import { uploadDraft } from './caseApi'

/**
 * The next draft version as Word + PDF, one action (Unit 58). Replaces the "Link to the draft"
 * field: the client now views and downloads the files themselves.
 */
export default function UploadDraft({ caseId, onUploaded }: { caseId: string; onUploaded: () => Promise<void> }) {
  const [docx, setDocx] = useState<File | null>(null)
  const [pdf, setPdf] = useState<File | null>(null)
  const [state, setState] = useState<'idle' | 'sending' | 'failed'>('idle')

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!docx || !pdf) return
    setState('sending')
    try {
      await uploadDraft(caseId, docx, pdf)
      setState('idle')
      await onUploaded()
    } catch {
      setState('failed')
    }
  }

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Upload draft</h2>
      <form onSubmit={submit} className="mt-3 flex flex-col gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>Word file (.docx)</span>
          <input type="file" accept=".docx" required onChange={(e) => setDocx(e.target.files?.[0] ?? null)} />
        </label>
        <label className="flex flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>PDF of the same draft</span>
          <input
            type="file"
            accept="application/pdf,.pdf"
            required
            onChange={(e) => setPdf(e.target.files?.[0] ?? null)}
          />
        </label>
        <button
          type="submit"
          disabled={!docx || !pdf || state === 'sending'}
          className="self-start rounded-md px-3 py-1.5 font-medium"
          style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
        >
          {state === 'sending' ? 'Uploading…' : 'Upload draft'}
        </button>
        {state === 'failed' && (
          <p role="alert" style={{ color: 'var(--status-red)' }}>
            The draft was not uploaded. Check both files are a real Word document and PDF under 15MB, then try
            again.
          </p>
        )}
      </form>
    </section>
  )
}
