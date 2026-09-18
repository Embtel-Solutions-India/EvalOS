import { useNavigate } from 'react-router-dom'

import BookingForm from './BookingForm'

/**
 * Booking a meeting, as its own screen.
 *
 * <p><strong>The sidebar is the way in</strong> (2026-09-17), the same move as Add opportunity and
 * Add lead. It was a button in the Meetings header, so booking meant first opening the diary — and
 * the diary is the screen you open to see what is already booked, not to add to it.
 *
 * <p><strong>Free slots are read live, and that is not a miss.</strong> Availability is GHL's to
 * compute from open hours, buffers, caps and the assignee's other appointments; a mirrored slot is
 * wrong within a minute of being written (D48). The calendar and team-member lists behind this form
 * ARE mirrored (Unit 47), so only the slots cost a round trip.
 */
export default function NewMeetingPage() {
  const navigate = useNavigate()

  // A form is read down a column, not across a monitor: capped rather than left to fill, which is
  // what left the fields stranded beside acres of white.
  return (
    <section className="max-w-5xl space-y-4">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">Add meeting</h1>
        <p className="text-sm text-slate-500">
          Booked into GoHighLevel, which is what sends the invitation to the client.
        </p>
      </header>

      {/* No loading state here: the form owns its own calendar and slot reads, and a skeleton
          around it would be a spinner for a request this screen does not make. */}
      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <BookingForm onBooked={() => navigate('/meetings')} />
      </div>
    </section>
  )
}
