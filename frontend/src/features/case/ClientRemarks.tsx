import { useMutation, useQuery } from '@tanstack/react-query'
import { MessageSquareText } from 'lucide-react'
import { useState } from 'react'
import { Panel } from '../../components/ui/panel'
import type { Role } from '../../lib/session'
import { fetchClientRemarks, postClientRemark, type CaseDetail } from './caseApi'

/** The roles the server lets write a remark (`ClientRemarkController.MAY_WRITE`); a brand manager reads only. */
const MAY_WRITE: readonly Role[] = ['GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER']
const MAY_READ: readonly Role[] = [...MAY_WRITE, 'BRAND_MANAGER']

/**
 * What the client reads under their case's status (D74). Kept apart from the timeline's internal
 * notes on purpose: everything on this panel is shown to the client, and nothing typed anywhere
 * else is. A remark cannot be edited — a correction is a newer remark.
 */
export default function ClientRemarks({ detail, role }: { detail: CaseDetail; role: Role }) {
  const caseId = detail.summary.id
  const readable = detail.maySeeCaseContent && MAY_READ.includes(role)
  const [body, setBody] = useState('')
  const remarks = useQuery({
    queryKey: ['case', caseId, 'client-remarks'],
    queryFn: ({ signal }) => fetchClientRemarks(caseId, signal),
    enabled: readable,
  })
  const post = useMutation({
    mutationFn: () => postClientRemark(caseId, body),
    onSuccess: () => setBody(''),
  })

  if (!readable) return null

  const latestFirst = [...(remarks.data ?? [])].reverse()
  return (
    <Panel title="Update for the client" icon={<MessageSquareText />}>
      <p className="mb-2 text-xs" style={{ color: 'var(--text-muted)' }}>
        The client sees every update here, under the case's status. Internal notes belong in the timeline.
      </p>
      {MAY_WRITE.includes(role) && (
        <form
          className="mb-3 flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (body.trim()) post.mutate()
          }}
        >
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={2000}
            rows={3}
            aria-label="Update for the client"
            className="rounded-md border p-1.5 text-sm"
          />
          <button
            type="submit"
            disabled={!body.trim() || post.isPending}
            className="self-start rounded-md px-3 py-1.5 text-sm font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Send to client
          </button>
          {post.isError && (
            <p className="text-sm" style={{ color: 'var(--status-red)' }}>
              Could not save the update.
            </p>
          )}
        </form>
      )}
      {latestFirst.length === 0 ?
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing has been sent to the client yet.
        </p>
      : <ol className="flex flex-col gap-2">
          {latestFirst.map((r) => (
            <li key={r.id} className="text-sm">
              <p className="whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
                {r.body}
              </p>
              <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                {r.authorName ?? 'Staff'}, {new Date(r.createdAt).toLocaleString()}
              </p>
            </li>
          ))}
        </ol>
      }
    </Panel>
  )
}
