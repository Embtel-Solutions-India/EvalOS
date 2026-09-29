import { useState } from 'react'
import { Link } from 'react-router-dom'

import { moveStage } from './opportunityApi'

/**
 * The ENM's actions on a hiring candidate (Unit 63): move the stage, and — once they are hired —
 * open the expert database's create form pre-filled with who they are. Nothing is created here: a
 * roster row needs a fee and fields a stage move cannot supply.
 */
export default function HiringActions({
  opportunityId,
  stages,
  candidate,
  onChanged,
}: {
  opportunityId: string
  stages: readonly { stageId: string; stageName: string }[]
  candidate: { name: string; email: string | null; phone: string | null }
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const prefill = new URLSearchParams({ new: '1', fullName: candidate.name, source: 'Hiring pipeline' })
  if (candidate.email) prefill.set('email', candidate.email)
  if (candidate.phone) prefill.set('phone', candidate.phone)

  return (
    <div className="space-y-4">
      <label className="block text-xs" style={{ color: 'var(--text-muted)' }}>
        Move to stage
        <select
          disabled={busy}
          defaultValue=""
          onChange={async (event) => {
            const stageId = event.target.value
            if (!stageId) return
            setBusy(true)
            setError(null)
            try {
              await moveStage(opportunityId, stageId)
              onChanged()
            } catch (failure) {
              setError(failure instanceof Error ? failure.message : 'That did not work')
            } finally {
              setBusy(false)
            }
          }}
          className="field mt-1 w-full"
        >
          <option value="">Choose…</option>
          {stages.map((stage) => (
            <option key={stage.stageId} value={stage.stageId}>
              {stage.stageName}
            </option>
          ))}
        </select>
      </label>

      <Link to={`/experts?${prefill}`} className="btn w-full justify-center">
        Add to expert database
      </Link>

      {error && (
        <p className="text-sm" role="alert" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </div>
  )
}
