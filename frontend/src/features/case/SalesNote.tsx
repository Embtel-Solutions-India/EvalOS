import type { CaseDetail } from './caseApi'

/** The note Sales wrote with the win (D70): the production team's first read on a new case. */
export default function SalesNote({ detail }: { detail: CaseDetail }) {
  const note = detail.salesNote
  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Sales handoff note</h2>

      {!detail.maySeeCaseContent ?
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          Not visible to your role.
        </p>
      : note ?
        <>
          <p className="mt-2 text-sm whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
            {note.body}
          </p>
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            {note.author ?? 'Sales'} · {new Date(note.writtenAt).toLocaleString()}
          </p>
        </>
      : <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No note. The deal was won outside EvalOS.
        </p>
      }
    </section>
  )
}
