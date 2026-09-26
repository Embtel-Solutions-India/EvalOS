import { useEffect, useState } from 'react'
import { fetchDraftComments, postDraftComment, type DraftComment } from './caseApi'

/** One version's thread with the client (Unit 58). Read-only unless the version is in client review. */
export default function DraftComments({ caseId, draftId, open }: { caseId: string; draftId: string; open: boolean }) {
  const [thread, setThread] = useState<DraftComment[] | null>(null)
  const [body, setBody] = useState('')
  const [page, setPage] = useState('')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetchDraftComments(caseId, draftId)
      .then(setThread)
      .catch(() => setFailed(true))
  }, [caseId, draftId])

  async function send(event: React.FormEvent) {
    event.preventDefault()
    const text = body.trim()
    if (!text) return
    try {
      const added = await postDraftComment(caseId, draftId, text, page ? Number(page) : null)
      setThread((current) => [...(current ?? []), added])
      setBody('')
      setPage('')
    } catch {
      setFailed(true)
    }
  }

  if (failed) {
    return (
      <p className="text-sm" style={{ color: 'var(--status-red)' }}>
        Could not load or send comments.
      </p>
    )
  }
  if (!thread) return null

  return (
    <div className="mt-2 flex flex-col gap-2">
      {thread.length === 0 && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          No comments on this version.
        </p>
      )}
      <ul className="flex flex-col gap-1">
        {thread.map((c) => (
          <li key={c.id} className="text-sm">
            <span className="font-medium">{c.authorKind === 'CLIENT' ? 'Client' : (c.authorName ?? 'Case team')}</span>
            {c.page != null && <span style={{ color: 'var(--text-muted)' }}> · page {c.page}</span>}
            <span style={{ color: 'var(--text-muted)' }}> · {new Date(c.createdAt).toLocaleString()}</span>
            <p className="whitespace-pre-wrap">{c.body}</p>
          </li>
        ))}
      </ul>
      {open && (
        <form onSubmit={send} className="flex flex-wrap items-end gap-2">
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={2000}
            rows={2}
            aria-label="Comment"
            className="min-w-[16rem] flex-1 rounded-md border p-1.5 text-sm"
          />
          <input
            type="number"
            min={1}
            value={page}
            onChange={(e) => setPage(e.target.value)}
            aria-label="Page (optional)"
            placeholder="Page"
            className="w-20 rounded-md border p-1.5 text-sm"
          />
          <button
            type="submit"
            className="rounded-md px-3 py-1.5 text-sm font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Comment
          </button>
        </form>
      )}
    </div>
  )
}
