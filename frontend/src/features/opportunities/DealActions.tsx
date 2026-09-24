import { useEffect, useState } from 'react'
import {
  bookMeeting,
  closeDeal,
  fetchCalendars,
  moveStage,
  setFollowUp,
  type Calendar,
  type CloseStatus,
} from './opportunityApi'

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
 * **Booking a meeting is not the same as setting a follow-up, and both are here.** A follow-up
 * is a GHL *task* — a private reminder for the salesperson. A meeting is a real appointment on a
 * real calendar, and **booking it runs GHL's automations**, which is how the client is actually
 * invited. EvalOS still sends nothing itself.
 *
 * **The meeting form guards against a double-submit harder than the others**, because it is the
 * one action with no idempotency behind it: GHL has no upsert for appointments, so two presses
 * are two meetings in somebody's diary. The button disables on `busy` like the rest, and the
 * form clears on success so a second press has nothing to send.
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
  const [calendars, setCalendars] = useState<readonly Calendar[]>([])
  const [calendarId, setCalendarId] = useState('')
  const [meetingTitle, setMeetingTitle] = useState('')
  const [meetingStart, setMeetingStart] = useState('')
  const [meetingMinutes, setMeetingMinutes] = useState(30)
  const [meetingBooked, setMeetingBooked] = useState<string | null>(null)

  /*
    Loaded once per card rather than per keystroke. The list is small and changes rarely, and a
    failure here is deliberately silent: `calendars/events.write` is the one grant in this
    programme never verified, so an empty list disables the form rather than putting a scary
    error on a card whose other four actions work.
  */
  useEffect(() => {
    const abort = new AbortController()
    fetchCalendars(abort.signal)
      .then(setCalendars)
      .catch(() => setCalendars([]))
    return () => abort.abort()
  }, [])

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
    <div className="space-y-4">
      <label className="block text-xs" style={{ color: 'var(--text-muted)' }}>
        Move to stage
        <select
          disabled={busy}
          defaultValue=""
          onChange={(event) => {
            const stageId = event.target.value
            if (stageId) run(() => moveStage(opportunityId, stageId))
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

      <div className="flex gap-2">
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
            className="btn flex-1 capitalize"
          >
            {status}
          </button>
        ))}
      </div>

      {awaitingCase && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
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
        className="space-y-2"
      >
        <input
          value={followUpTitle}
          onChange={(event) => setFollowUpTitle(event.target.value)}
          placeholder="Follow up on…"
          className="field w-full"
        />
        <div className="flex gap-2">
          {/*
            A native date-time input rather than a picker library: the browser already has one,
            it is keyboard- and screen-reader-accessible for free, and it localises itself.
          */}
          <input
            type="datetime-local"
            value={followUpDue}
            onChange={(event) => setFollowUpDue(event.target.value)}
            className="field flex-1"
          />
          <button
            type="submit"
            disabled={busy || !followUpTitle.trim() || !followUpDue}
            className="btn btn-primary"
          >
            Set
          </button>
        </div>
      </form>

      {followUpSet && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          Follow-up “{followUpSet}” added as a task in GHL.
        </p>
      )}

      {calendars.length > 0 && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!calendarId || !meetingTitle.trim() || !meetingStart) return
            const start = new Date(meetingStart)
            const end = new Date(start.getTime() + meetingMinutes * 60_000)
            run(
              () =>
                bookMeeting(opportunityId, {
                  calendarId,
                  contactId,
                  title: meetingTitle.trim(),
                  startTime: start.toISOString(),
                  endTime: end.toISOString(),
                }),
              () => {
                setMeetingBooked(meetingTitle.trim())
                setMeetingTitle('')
                setMeetingStart('')
              },
            )
          }}
          className="space-y-2 border-t pt-4"
          style={{ borderColor: 'var(--border-default)' }}
        >
          <select
            value={calendarId}
            onChange={(event) => setCalendarId(event.target.value)}
            className="field w-full"
          >
            <option value="">Book a meeting…</option>
            {calendars.map((calendar) => (
              <option key={calendar.id} value={calendar.id}>
                {calendar.name}
              </option>
            ))}
          </select>
          {calendarId && (
            <>
              <input
                value={meetingTitle}
                onChange={(event) => setMeetingTitle(event.target.value)}
                placeholder="Meeting about…"
                className="field w-full"
              />
              <div className="flex gap-2">
                <input
                  type="datetime-local"
                  value={meetingStart}
                  onChange={(event) => setMeetingStart(event.target.value)}
                  className="field flex-1"
                />
                {/*
                  A duration rather than a second datetime: every meeting on this desk is 15-60
                  minutes, and picking an end date invites the end-before-start mistake the
                  server then has to refuse.
                */}
                <select
                  value={meetingMinutes}
                  onChange={(event) => setMeetingMinutes(Number(event.target.value))}
                  className="field w-auto"
                >
                  {[15, 30, 45, 60].map((minutes) => (
                    <option key={minutes} value={minutes}>
                      {minutes}m
                    </option>
                  ))}
                </select>
                <button
                  type="submit"
                  disabled={busy || !meetingTitle.trim() || !meetingStart}
                  className="btn btn-primary"
                >
                  Book
                </button>
              </div>
            </>
          )}
        </form>
      )}

      {meetingBooked && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {/*
            Said explicitly, because it is the one action here that reaches the client. A
            salesperson who does not know GHL sent the invite will send a second one by hand.
          */}
          “{meetingBooked}” booked. GHL sends the invitation.
        </p>
      )}
      {error && (
        <p className="text-xs" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </div>
  )
}
