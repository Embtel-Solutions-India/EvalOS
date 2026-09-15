import { useCallback, useMemo, useState } from 'react'
import { Card, KpiCard } from '../../components/ui/card'
import { SheetContent, SheetRoot } from '../../components/ui/dialog'
import { fetchDiary, type DiaryMeeting } from '../opportunities/opportunityApi'
import BookingForm from './BookingForm'
import { useMetrics } from '../dashboards/useMetrics'

/**
 * The salesperson's diary: what is booked, and one place to book more.
 *
 * <p><strong>Why this screen exists at all.</strong> Booking lived only inside a deal card on the
 * opportunity board, which made the board carry three jobs at once and made a meeting invisible
 * the moment the card scrolled away. Worse, EvalOS wrote meetings to GHL and had no route that
 * read them back, so "what am I doing on Thursday" was a question the product could not answer
 * about work it had itself created. `V47__meeting.sql` closed that by mirroring the booking
 * response; this is the screen that spends it.
 *
 * <p><strong>Grouped by day, not listed flat.</strong> A diary is read by "when", so the day is
 * the heading and the time is the row. A flat list sorted by date makes the reader do the
 * grouping in their head every time.
 *
 * <p><strong>The window is 30 days and the past is excluded.</strong> A diary answers "what is
 * coming"; last month's calls belong on the deal, not here. Stated because the endpoint takes an
 * arbitrary window and the choice is this screen's, not the server's.
 *
 * <p><strong>Booking is a sheet, not a page.</strong> `ui-context.md` makes a side sheet the
 * pattern for a record opened from a list — the reader keeps their place, and a booking that is
 * abandoned costs nothing. The form asks for the deal first because every other field depends on
 * it: GHL hangs an appointment off the contact, and the contact comes from the deal.
 */
export default function MeetingsPage() {
  const window30 = useMemo(() => {
    const from = new Date()
    const to = new Date(from.getTime() + 30 * 24 * 60 * 60 * 1000)
    return { from, to }
  }, [])

  const [reloads, setReloads] = useState(0)
  const reload = useCallback(() => setReloads((n) => n + 1), [])

  const { data, state } = useMetrics<readonly DiaryMeeting[]>(
    (signal) => fetchDiary(window30.from, window30.to, signal),
    [window30, reloads],
  )

  const [booking, setBooking] = useState(false)

  const meetings = data ?? []
  const today = meetings.filter((m) => isSameDay(new Date(m.startsAt), new Date()))
  const week = meetings.filter((m) => withinDays(m.startsAt, 7))
  const byDay = groupByDay(meetings)

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Meetings</h1>
        <button
          type="button"
          onClick={() => setBooking(true)}
          className="rounded-lg px-3 py-1.5 text-sm font-medium"
          style={{ background: 'var(--accent)', color: 'var(--accent-contrast, #fff)' }}
        >
          Book a meeting
        </button>
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-3">
        <KpiCard title="Today" state={state} value={data ? today.length : null} />
        <KpiCard title="Next 7 days" state={state} value={data ? week.length : null} />
        <KpiCard
          title="Next 30 days"
          state={state}
          value={data ? meetings.length : null}
          note="Booked through EvalOS. A meeting created directly in GHL appears once the sync lands."
        />
      </div>

      <div className="mt-4">
        <Card
          title="Diary"
          state={
            state.kind === 'ok' && meetings.length === 0
              ? {
                  kind: 'empty',
                  note: 'Nothing booked in the next 30 days. Use “Book a meeting” to add one.',
                }
              : state
          }
        >
          <div className="grid gap-4">
            {byDay.map(([day, rows]) => (
              <div key={day}>
                <h3
                  className="text-xs font-medium uppercase tracking-wide"
                  style={{ color: 'var(--text-muted)' }}
                >
                  {day}
                </h3>
                <ul className="mt-1 divide-y" style={{ borderColor: 'var(--border-subtle)' }}>
                  {rows.map((m) => (
                    <li key={m.appointmentId} className="flex items-baseline gap-3 py-1.5">
                      <span className="font-num w-28 shrink-0 text-sm tabular-nums">
                        {timeOf(m.startsAt)}–{timeOf(m.endsAt)}
                      </span>
                      <span className="flex-1 truncate text-sm">{m.title}</span>
                      {m.status && (
                        <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                          {m.status}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <SheetRoot open={booking} onOpenChange={setBooking}>
        <SheetContent
          title="Book appointment"
          description="Booked into GHL, which is what sends the invitation to the client."
        >
          <BookingForm
            onBooked={() => {
              setBooking(false)
              reload()
            }}
          />
        </SheetContent>
      </SheetRoot>
    </section>
  )
}

function isSameDay(a: Date, b: Date): boolean {
  return a.toDateString() === b.toDateString()
}

function withinDays(iso: string, days: number): boolean {
  return Date.parse(iso) <= Date.now() + days * 24 * 60 * 60 * 1000
}

function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

/** Day heading → that day's meetings, in the order the server already sorted them. */
function groupByDay(meetings: readonly DiaryMeeting[]): [string, DiaryMeeting[]][] {
  const days = new Map<string, DiaryMeeting[]>()
  for (const meeting of meetings) {
    const key = new Date(meeting.startsAt).toLocaleDateString(undefined, {
      weekday: 'long',
      day: 'numeric',
      month: 'short',
    })
    const rows = days.get(key)
    if (rows) rows.push(meeting)
    else days.set(key, [meeting])
  }
  return [...days.entries()]
}
