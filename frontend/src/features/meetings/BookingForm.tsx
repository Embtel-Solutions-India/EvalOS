import { useEffect, useMemo, useState } from 'react'
import {
  bookMeeting,
  fetchCalendars,
  fetchGhlUsers,
  fetchOpportunityBoard,
  fetchSlots,
  type Calendar,
  type Deal,
  type FreeSlots,
  type GhlUser,
} from '../opportunities/opportunityApi'
import { useMetrics } from '../dashboards/useMetrics'

/**
 * The booking dialog, matched against GHL's own "Book appointment" screen.
 *
 * <p><strong>Two columns, because the screen has two jobs.</strong> The left is the appointment —
 * what, when, who takes it. The right is the people — the contact, and what staff want to remember
 * about the call. GHL splits it that way and the split is right: the contact is chosen once and
 * the appointment fields are worked through in order.
 *
 * <p><strong>Slots, not a duration.</strong> An earlier version asked for a start time and a
 * duration from a dropdown. That was wrong for this domain: every calendar here defines
 * {@code slotDuration: 30}, and availability depends on open hours, buffers, per-day caps, the
 * team member's other appointments and minimum notice — all of it GHL configuration. Asking GHL
 * for its free slots is the only way the two agree; calculating them here would produce a second
 * answer that is wrong the moment somebody edits the calendar.
 *
 * <p><strong>Default | Custom is GHL's own framing and is kept.</strong> Default books into a slot
 * the calendar says is free. Custom sends {@code ignoreFreeSlotValidation}, which stops GHL
 * refusing collisions — so a double-booking becomes a silent success instead of a visible
 * failure. It is a mode the salesperson chooses, never a default, and the copy says what it costs.
 *
 * <p><strong>Inactive calendars are filtered out.</strong> The location has twelve and five are
 * active; GHL refuses a booking on an inactive one with "Calendar is inactive", so offering them
 * would be offering a guaranteed failure.
 *
 * <p><strong>Two fields GHL's dialog has that its API does not:</strong> <em>Add guests</em> (no
 * guest list in the appointment body) and <em>Blocked off time</em> (the other tab, a different
 * endpoint, and it books nobody). <em>Internal notes</em> is here and is a second GHL call the
 * server makes after the appointment exists.
 */
export default function BookingForm({ onBooked }: { onBooked: () => void }) {
  const { data: board } = useMetrics((signal) => fetchOpportunityBoard(signal), [])
  const { data: calendars } = useMetrics<readonly Calendar[]>(
    (signal) => fetchCalendars(signal),
    [],
  )
  const { data: users } = useMetrics<readonly GhlUser[]>((signal) => fetchGhlUsers(signal), [])

  const deals: readonly Deal[] = useMemo(
    () => (board?.columns ?? []).flatMap((column) => column.deals),
    [board],
  )
  const bookable = useMemo(() => (calendars ?? []).filter((c) => c.active), [calendars])

  const [opportunityId, setOpportunityId] = useState('')
  const [calendarId, setCalendarId] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [showDescription, setShowDescription] = useState(false)
  const [assignedUserId, setAssignedUserId] = useState('')
  const [locationType, setLocationType] = useState('')
  const [address, setAddress] = useState('')
  const [internalNote, setInternalNote] = useState('')
  const [showNote, setShowNote] = useState(false)

  /** GHL's Default | Custom. Default is a real slot; Custom is any time, validation off. */
  const [mode, setMode] = useState<'default' | 'custom'>('default')
  const [day, setDay] = useState('')
  const [slot, setSlot] = useState('')
  const [customStart, setCustomStart] = useState('')
  const [customMinutes, setCustomMinutes] = useState(30)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const calendar = bookable.find((c) => c.id === calendarId)
  const deal = deals.find((d) => d.opportunityId === opportunityId)

  /**
   * The browser's own zone is the default, and it is shown rather than assumed.
   *
   * GHL renders slots in whatever zone it is asked for and labels the control "Showing slots in
   * this timezone". A booking screen that does not say which zone it is showing is how a meeting
   * lands at 3am — so the label is not decoration.
   */
  const timezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone, [])

  const { data: slots, state: slotState } = useMetrics<FreeSlots | null>(
    (signal) => {
      if (calendarId === '' || mode === 'custom') return Promise.resolve(null)
      const from = new Date()
      const to = new Date(from.getTime() + 21 * 24 * 60 * 60 * 1000)
      return fetchSlots(calendarId, from, to, timezone, signal)
    },
    [calendarId, mode, timezone],
  )

  const days = useMemo(() => Object.keys(slots?.byDate ?? {}).sort(), [slots])
  // Pick the first day with availability so the form opens on something usable rather than empty.
  useEffect(() => {
    if (days.length > 0 && !days.includes(day)) setDay(days[0])
  }, [days, day])
  useEffect(() => setSlot(''), [day, calendarId])

  // The calendar's own default title, exactly as GHL shows it, and only while untouched.
  useEffect(() => {
    if (calendar && title === '') setTitle(calendar.titleTemplate ?? '')
  }, [calendar, title])

  const slotsForDay = slots?.byDate[day] ?? []
  const minutes = calendar?.slotMinutes ?? 30
  const ready =
    deal !== undefined &&
    calendarId !== '' &&
    title.trim() !== '' &&
    (mode === 'default' ? slot !== '' : customStart !== '')

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!ready || deal === undefined) return
    setBusy(true)
    setError(null)
    try {
      const start = mode === 'default' ? new Date(slot) : new Date(customStart)
      const span = mode === 'default' ? minutes : customMinutes
      await bookMeeting(opportunityId, {
        calendarId,
        contactId: deal.contactId,
        title: title.trim(),
        startTime: start.toISOString(),
        endTime: new Date(start.getTime() + span * 60_000).toISOString(),
        description: description.trim() || undefined,
        assignedUserId: assignedUserId || undefined,
        meetingLocationType: locationType || undefined,
        address: address.trim() || undefined,
        customTime: mode === 'custom',
        internalNote: internalNote.trim() || undefined,
      })
      onBooked()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The booking was refused.')
    } finally {
      setBusy(false)
    }
  }

  // **`minmax(0, …)` and `min-w-0`, not `1fr` and `16rem`.** A grid child defaults to
  // `min-width: auto`, so it refuses to shrink below its content — the right column measured 412px
  // of content in a 256px track, which pushed the whole page into a horizontal scrollbar and cut
  // the contact picker off at the viewport edge. `1fr` is shorthand for `minmax(auto, 1fr)`, the
  // same trap. It never showed while this form only ever rendered inside a 30rem sheet.
  return (
    <form onSubmit={submit} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
      {/* Left: the appointment. */}
      <div className="grid min-w-0 content-start gap-3">
        <Select
          label="Calendar"
          value={calendarId}
          onChange={setCalendarId}
          options={bookable.map((c) => ({ value: c.id, label: c.name }))}
          placeholder="Choose a calendar…"
        />
        {calendars && bookable.length < calendars.length && (
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {calendars.length - bookable.length} inactive calendar
            {calendars.length - bookable.length === 1 ? '' : 's'} hidden — GHL refuses bookings on
            them.
          </p>
        )}

        <Text label="Appointment title" value={title} onChange={setTitle} />
        {showDescription ? (
          <label className="grid gap-1 text-sm">
            <span style={{ color: 'var(--text-muted)' }}>Description</span>
            <textarea
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="rounded-lg border px-2 py-1.5"
              style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
            />
          </label>
        ) : (
          <button
            type="button"
            onClick={() => setShowDescription(true)}
            className="justify-self-start text-sm"
            style={{ color: 'var(--accent-primary)' }}
          >
            Add description
          </button>
        )}

        <Select
          label="Team member"
          value={assignedUserId}
          onChange={setAssignedUserId}
          options={(users ?? []).map((u) => ({ value: u.id, label: u.name }))}
          placeholder="Calendar Default"
        />

        <fieldset
          className="grid gap-2 rounded-lg border p-3"
          style={{ borderColor: 'var(--border-default)', background: 'var(--bg-subtle, transparent)' }}
        >
          <legend className="px-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            Date &amp; time
          </legend>
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Showing slots in <strong>{slots?.timezone ?? timezone}</strong>
          </p>

          <div className="flex gap-1">
            {(['default', 'custom'] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setMode(option)}
                className="rounded-lg px-3 py-1 text-sm"
                style={{
                  background: mode === option ? 'var(--accent-primary)' : 'transparent',
                  color: mode === option ? '#fff' : 'var(--text-muted)',
                  border: `1px solid ${mode === option ? 'var(--accent-primary)' : 'var(--border-default)'}`,
                }}
              >
                {option === 'default' ? 'Default' : 'Custom'}
              </button>
            ))}
          </div>

          {mode === 'default' ? (
            calendarId === '' ? (
              <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
                Choose a calendar to see its free slots.
              </p>
            ) : slotState.kind === 'loading' ? (
              <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
                Asking GHL for free slots…
              </p>
            ) : days.length === 0 ? (
              <p className="text-sm" style={{ color: 'var(--status-amber)' }}>
                No free slots in the next three weeks. Use Custom, or pick another calendar.
              </p>
            ) : (
              <div className="grid grid-cols-2 gap-3">
                <Select
                  label="Date"
                  value={day}
                  onChange={setDay}
                  options={days.map((d) => ({ value: d, label: longDate(d) }))}
                />
                <Select
                  label="Slot"
                  value={slot}
                  onChange={setSlot}
                  options={slotsForDay.map((s) => ({ value: s, label: slotLabel(s, minutes) }))}
                  placeholder="Choose a slot…"
                />
              </div>
            )
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <Text
                  label="Starts"
                  value={customStart}
                  onChange={setCustomStart}
                  type="datetime-local"
                />
                <Select
                  label="Duration"
                  value={String(customMinutes)}
                  onChange={(v) => setCustomMinutes(Number(v))}
                  options={[15, 30, 45, 60, 90].map((m) => ({
                    value: String(m),
                    label: `${m} minutes`,
                  }))}
                />
              </div>
              <p className="text-xs" style={{ color: 'var(--status-amber)' }}>
                Custom skips GHL's availability check — it will not warn you about a clash.
              </p>
            </>
          )}
        </fieldset>

        <Select
          label="Meeting location"
          value={locationType}
          onChange={setLocationType}
          options={[
            { value: 'custom', label: 'Custom' },
            { value: 'zoom', label: 'Zoom' },
            { value: 'gmeet', label: 'Google Meet' },
            { value: 'ms_teams', label: 'Microsoft Teams' },
            { value: 'phone', label: 'Phone' },
            { value: 'address', label: 'In person' },
          ]}
          placeholder="Calendar default"
        />
        {locationType !== '' && locationType !== 'zoom' && locationType !== 'gmeet' && (
          <Text
            label={locationType === 'phone' ? 'Phone number' : 'Address or link'}
            value={address}
            onChange={setAddress}
          />
        )}
      </div>

      {/* Right: the people, and what staff want to remember. */}
      <aside className="grid min-w-0 content-start gap-3">
        <Select
          label="Contact"
          value={opportunityId}
          onChange={setOpportunityId}
          options={deals.map((d) => ({ value: d.opportunityId, label: d.name ?? d.opportunityId }))}
          placeholder="Choose a deal…"
        />
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          Picked by deal rather than searched: GHL hangs an appointment off the contact, and the
          contact comes from the deal you are working.
        </p>

        {showNote ? (
          <label className="grid gap-1 text-sm">
            <span style={{ color: 'var(--text-muted)' }}>Internal note</span>
            <textarea
              rows={4}
              maxLength={5000}
              value={internalNote}
              onChange={(e) => setInternalNote(e.target.value)}
              className="rounded-lg border px-2 py-1.5"
              style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
            />
            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
              Staff only — the client never sees this.
            </span>
          </label>
        ) : (
          <button
            type="button"
            onClick={() => setShowNote(true)}
            className="justify-self-start text-sm"
            style={{ color: 'var(--accent-primary)' }}
          >
            + Add internal note
          </button>
        )}
      </aside>

      <div className="grid min-w-0 gap-2 lg:col-span-2">
        {error && (
          <p className="text-sm" style={{ color: 'var(--status-red)' }} role="alert">
            {error}
          </p>
        )}
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          Booking runs GHL's automations, which is how the client gets the invitation. Booking
          twice creates two meetings — GHL has no way to recognise a repeat.
        </p>
        {/* **`--accent-primary`, not `--accent`.** There is no `--accent` token — `tokens.css`
            defines `--accent-primary` — so `background: var(--accent)` resolved to nothing and this
            button rendered white text on a transparent background: invisible on a white card, with
            no error anywhere. Eleven usages across three files had the same typo. */}
        <button
          type="submit"
          disabled={!ready || busy}
          className="justify-self-start rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
          style={{ background: 'var(--accent-primary)', color: '#fff' }}
        >
          {busy ? 'Booking…' : 'Book meeting'}
        </button>
      </div>
    </form>
  )
}

function Select({
  label,
  value,
  onChange,
  options,
  placeholder,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: readonly { value: string; label: string }[]
  placeholder?: string
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border px-2 py-1.5"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
      >
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function Text({
  label,
  value,
  onChange,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (value: string) => void
  type?: string
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border px-2 py-1.5"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
      />
    </label>
  )
}

/** `2026-09-15` → `Tue, Sep 15th`. GHL labels its date picker the same way. */
function longDate(iso: string): string {
  return new Date(`${iso}T12:00:00`).toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  })
}

/**
 * `2026-09-15T15:00:00-07:00` → `3:00 PM – 3:30 PM`.
 *
 * The end is derived from the calendar's own slot length rather than sent by GHL, which returns
 * only start times.
 */
function slotLabel(iso: string, minutes: number): string {
  const start = new Date(iso)
  const end = new Date(start.getTime() + minutes * 60_000)
  const time = (d: Date) => d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  return `${time(start)} – ${time(end)}`
}
