import { useState } from 'react'
import { closeDeal, moveStage, setFollowUp, type CloseStatus } from './opportunityApi'

/**
 * What a salesperson can do to a deal without opening GHL.
 *
 * **Winning is the one that needs explaining on screen.** EvalOS tells GHL the deal is won and
 * GHL's webhook creates the case — those are two systems and there is a gap between them. If the
 * button just went quiet, the salesperson presses it again. So the pending state is shown and
 * said out loud.
 *
 * **There is no "move to another pipeline".** Promotion from marketing to sales is GHL's
 * workflow; a button here would race the automation the business already owns.
 *
 * **And no "book a meeting".** Not an omission: `calendars/events.write` and
 * `calendars.readonly` are not granted. Follow-ups are here because a GHL task needs only
 * `contacts.write`, which is.
 */
export default function DealActions({
  opportunityId,
  contactId,
  stages,
  onChanged,
}: {
  opportunityId: string
  contactId: string
  stages: readonly { stageId: string; stageName: string }[]
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [awaitingCase, setAwaitingCase] = useState(false)
  const [followUpTitle, setFollowUpTitle] = useState('')
  const [followUpDue, setFollowUpDue] = useState('')
  const [followUpSet, setFollowUpSet] = useState<string | null>(null)

  async function run(action: () => Promise<unknown>, after?: () => void) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
      after?.()
      onChanged()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'That did not work')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2 border-t border-slate-200 pt-2">
      <label className="block text-xs text-slate-600">
        Move to stage
        <select
          disabled={busy}
          defaultValue=""
          onChange={(event) => {
            const stageId = event.target.value
            if (stageId) run(() => moveStage(opportunityId, stageId))
          }}
          className="mt-0.5 w-full rounded border border-slate-300 px-2 py-1 text-xs"
        >
          <option value="">Choose…</option>
          {stages.map((stage) => (
            <option key={stage.stageId} value={stage.stageId}>
              {stage.stageName}
            </option>
          ))}
        </select>
      </label>

      <div className="flex gap-1">
        {(['won', 'lost', 'abandoned'] as CloseStatus[]).map((status) => (
          <button
            key={status}
            type="button"
            disabled={busy}
            onClick={() =>
              run(
                () => closeDeal(opportunityId, status),
                // Only winning starts a case. Lost and abandoned end the deal and nothing
                // downstream happens, so promising a case for those would be a lie.
                () => setAwaitingCase(status === 'won'),
              )
            }
            className="flex-1 rounded border border-slate-300 px-2 py-1 text-xs capitalize disabled:opacity-40"
          >
            {status}
          </button>
        ))}
      </div>

      {awaitingCase && (
        <p className="text-xs text-amber-700">
          {/*
            The honest description of a two-system handoff. EvalOS does not create the case and
            must not pretend to: GHL fires `opportunity.won` and the webhook does it (invariant
            8). Saying "created" here would be claiming something that has not happened yet.
          */}
          Marked won in GHL. The case appears here once GHL confirms it — usually a moment.
        </p>
      )}

      <form
        onSubmit={(event) => {
          event.preventDefault()
          if (!followUpTitle.trim() || !followUpDue) return
          run(
            () =>
              setFollowUp(opportunityId, {
                contactId,
                title: followUpTitle.trim(),
                dueAt: new Date(followUpDue).toISOString(),
              }),
            () => {
              setFollowUpSet(followUpTitle.trim())
              setFollowUpTitle('')
              setFollowUpDue('')
            },
          )
        }}
        className="space-y-1"
      >
        <input
          value={followUpTitle}
          onChange={(event) => setFollowUpTitle(event.target.value)}
          placeholder="Follow up on…"
          className="w-full rounded border border-slate-300 px-2 py-1 text-xs"
        />
        <div className="flex gap-1">
          {/*
            A native date-time input rather than a picker library: the browser already has one,
            it is keyboard- and screen-reader-accessible for free, and it localises itself.
          */}
          <input
            type="datetime-local"
            value={followUpDue}
            onChange={(event) => setFollowUpDue(event.target.value)}
            className="flex-1 rounded border border-slate-300 px-2 py-1 text-xs"
          />
          <button
            type="submit"
            disabled={busy || !followUpTitle.trim() || !followUpDue}
            className="rounded bg-slate-900 px-2 py-1 text-xs text-white disabled:opacity-40"
          >
            Set
          </button>
        </div>
      </form>

      {followUpSet && (
        <p className="text-xs text-emerald-700">
          Follow-up “{followUpSet}” added as a task in GHL.
        </p>
      )}
      {error && <p className="text-xs text-rose-600">{error}</p>}
    </div>
  )
}
