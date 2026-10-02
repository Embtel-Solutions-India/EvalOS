import { Scale } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import type { CaseDetail } from './caseApi'

/**
 * Why this expert was chosen (Unit 32). A different audience from the PM's strategy notes: the
 * ENM and the Brand Manager read this and not the notes, the Case Manager the reverse. Read-only —
 * it is written where the expert is chosen. A role that may not read it does not see the panel.
 */
export default function ExpertRationale({ detail }: { detail: CaseDetail }) {
  if (!detail.maySeeExpertRationale) return null
  return (
    <Panel title="Expert selection reason" icon={<Scale />}>
      {detail.expertSelectionRationale ?
        <p className="text-sm whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
          {detail.expertSelectionRationale}
        </p>
      : <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          No reason recorded. It is written when the expert is assigned or reassigned.
        </p>
      }
    </Panel>
  )
}
