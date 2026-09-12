import { useQuery } from '@tanstack/react-query'
import { CalendarClock, Video } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { failureMessage, NO_TOKEN, type ClientMeeting } from '@shared/lib/portal'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { statusOf } from '@shared/services/apiClient'
import { listMeetings } from '@/services/meetingService'

/**
 * The client's meetings with the business.
 *
 * **A window onto GHL's calendar, not a second copy of it.** Sales books from their desk, GHL
 * sends the invitation and owns the appointment. Nothing here can be changed — there is
 * deliberately no reschedule and no cancel, because both would need to negotiate with a person
 * and a button that silently moved a salesperson's diary is not that negotiation.
 *
 * **Party-scoped, like `Invoices`.** A meeting belongs to the client, who may have several
 * cases, so a case-scoped link answers 403 and the message says which link they are holding.
 *
 * **⚠ The times are rendered as text and never parsed.** GHL sends `"2026-09-13 12:30:00"` with
 * no timezone offset. `new Date()` on that is implementation-defined — some browsers read it as
 * local, some as UTC — so constructing one would show a client a time that is right on one
 * machine and hours out on another. Since the payload carries no zone, there is no correct
 * instant to compute, and the honest rendering is GHL's own string tidied for reading. Fixing
 * this properly means GHL sending an offset, not the portal guessing one.
 */
export default function Meetings() {
  const tokenPresent = usePortalToken()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'meetings'],
    queryFn: listMeetings,
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Your meetings" description={NO_TOKEN} />
      </div>
    )
  }

  const status = statusOf(error)

  return (
    <div className="mx-auto max-w-4xl p-6">
      <PageHeader
        title="Your meetings"
        description="Calls and appointments arranged with you."
      />

      {isLoading && <TableSkeleton />}

      {isError && (
        <ErrorState
          description={
            // The same specific 403 as billing: the link opened is scoped to one case, and a
            // meeting belongs to the client. The generic message would send them to support
            // for something a different link fixes.
            status === 403
              ? 'This link opens one case rather than your account, so it cannot show your meetings. Ask us for your account link.'
              : failureMessage(status)
          }
          onRetry={() => void refetch()}
        />
      )}

      {!isLoading && !isError && data && (
        data.length === 0 ? (
          <EmptyState
            icon={CalendarClock}
            title="No meetings scheduled"
            description="Anything we arrange with you will appear here."
          />
        ) : (
          <ul className="space-y-3">
            {data.map((meeting) => (
              <MeetingCard key={meeting.id ?? crypto.randomUUID()} meeting={meeting} />
            ))}
          </ul>
        )
      )}
    </div>
  )
}

/**
 * Cards rather than a table: a meeting has a join link, which is the thing the client came for
 * and wants to be able to hit. A link buried in a table cell is a link people miss.
 */
function MeetingCard({ meeting }: { meeting: ClientMeeting }) {
  return (
    <li className="rounded-lg border border-slate-200 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-medium">{meeting.title ?? 'Meeting'}</p>
          <p className="text-sm text-slate-600">
            {when(meeting.startsAt, meeting.endsAt)}
          </p>
        </div>
        {/*
          GHL's own word, tidied for reading but never reinterpreted — the same rule the invoice
          status follows. Deciding here that `noshow` means `cancelled` would be EvalOS forming
          an opinion about somebody's attendance.
        */}
        <Badge variant="secondary">{(meeting.status ?? 'unknown').replace(/_/g, ' ')}</Badge>
      </div>

      {meeting.location && <Joining location={meeting.location} />}
    </li>
  )
}

/**
 * The join link, or the address.
 *
 * GHL puts both in one field, so this decides by looking. An `http` prefix is the only reliable
 * signal, and anything else is rendered as text rather than linkified — a street address wrapped
 * in an anchor is a dead link on a page a client is trusting.
 */
function Joining({ location }: { location: string }) {
  const isUrl = location.startsWith('http://') || location.startsWith('https://')

  if (!isUrl) {
    return <p className="mt-2 text-sm text-slate-600">{location}</p>
  }
  return (
    <a
      href={location}
      target="_blank"
      // noreferrer alongside noopener: the target must not be handed this portal's URL, which
      // carries the credential in its fragment.
      rel="noopener noreferrer"
      className="mt-2 inline-flex items-center gap-1.5 text-sm font-medium text-sky-700 hover:underline"
    >
      <Video className="h-4 w-4" aria-hidden />
      Join the meeting
    </a>
  )
}

/**
 * GHL's time strings, tidied — never parsed.
 *
 * Turns `"2026-09-13 12:30:00"` into `13 Sep 2026, 12:30` by reading the parts rather than
 * constructing a `Date`. See the file comment: the payload carries no offset, so there is no
 * correct instant to compute and a `Date` would invent a zone.
 */
function when(startsAt: string | null, endsAt: string | null): string {
  const start = readParts(startsAt)
  if (!start) return '—'
  const end = readParts(endsAt)
  return end ? `${start} – ${end.slice(-5)}` : start
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function readParts(value: string | null): string | null {
  if (!value) return null
  // `YYYY-MM-DD HH:MM:SS`, GHL's shape. Anything else is shown verbatim rather than mangled —
  // if GHL ever starts sending an offset, the raw string is still readable.
  const match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(value)
  if (!match) return value
  const [, year, month, day, hour, minute] = match
  const monthName = MONTHS[Number(month) - 1] ?? month
  return `${Number(day)} ${monthName} ${year}, ${hour}:${minute}`
}
