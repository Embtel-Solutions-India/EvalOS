import { Handshake } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import type { CaseDetail } from './caseApi'

/**
 * What Sales wrote when the deal was won (Unit 71, D70): the handoff to production. Behind
 * `maySeeCaseContent`, like the documents; a role that may not read it does not see the panel.
 */
export default function SalesNote({ detail }: { detail: CaseDetail }) {
  if (!detail.maySeeCaseContent) return null
  const note = detail.salesNote
  return (
    <Panel title="Sales handoff note" icon={<Handshake />}>
      {note ?
        <>
          <p className="text-sm whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
            {note.body}
          </p>
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            {note.author ?? 'Sales'}, {new Date(note.writtenAt).toLocaleString()}
          </p>
        </>
      : <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          No note. The deal was won outside EvalOS.
        </p>
      }
    </Panel>
  )
}
