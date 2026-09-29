package com.ie.evalos.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Meeting;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.repository.MeetingRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking a meeting on a deal you own.
 *
 * <p><strong>Every method starts at {@link PipelineScope#requireMine}</strong>, which is the
 * whole access model: a salesperson may act on an opportunity if and only if it sits in the one
 * pipeline their row names. Same first line as every method on {@code SalesDeskService}, and it
 * is the reason a meeting route needs no scope logic of its own.
 *
 * <p><strong>Validation happens here rather than at the client, and it is not decoration.</strong>
 * GHL treats {@code endTime} as optional and will happily book an appointment with no end, which
 * shows up in a calendar as a zero-length event nobody notices they have. Refusing is kinder
 * than creating one. The same goes for a start in the past: GHL accepts it, and a meeting
 * booked into last Tuesday is a mistake the salesperson would rather hear about now.
 *
 * <p><strong>There is no idempotency here and none is available</strong> — GHL offers no upsert
 * for appointments, so a double-submit books two meetings. Still true, and still stated rather
 * than papered over.
 *
 * <p><strong>EvalOS now holds a mirrored appointment row, reversing what this note used to
 * say.</strong> It read: "the fix would be EvalOS holding an appointment row, which is exactly
 * the local mirror the programme's truth model refuses." That was right under `00b`, where GHL
 * was truth and EvalOS cached nothing it was not the source of. `00c` moved the record: EvalOS
 * holds its own rows, syncs them, and keeps working with the sync switched off.
 *
 * <p>The practical cost of the old rule was that this desk was <strong>write-only</strong> — a
 * salesperson booked Thursday's call and no EvalOS screen could ever show it again, which defeats
 * the stated purpose of Units 36-41. The mirror is written from GHL's own response, never from
 * the request, so it records what GHL confirmed rather than what was asked for. `V47__meeting.sql`
 * carries the full argument and the three read routes that were compared.
 *
 * <p>It does <strong>not</strong> make EvalOS the authority: a meeting moved or cancelled in GHL
 * is not reflected here until Unit 45's sweep reconciles, and the row says so through
 * {@code synced_at}.
 */
@Service
public class SalesMeetingService {

	private static final Logger log = LoggerFactory.getLogger(SalesMeetingService.class);

	private final GhlCalendarClient calendars;
	/** Unit 47: the calendar LIST comes from here; {@link #slots} still asks GHL. */
	private final ReferenceMirrorService reference;
	private final PipelineScope scope;
	private final MeetingRepository meetings;
	/** Unit 60: the caller's GHL user, for their own blocked time. */
	private final TeamMemberRepository teamMembers;

	SalesMeetingService(GhlCalendarClient calendars, ReferenceMirrorService reference,
			PipelineScope scope,
			MeetingRepository meetings, TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
		this.calendars = calendars;
		this.reference = reference;
		this.scope = scope;
		this.meetings = meetings;
	}

	/**
	 * The calendars a meeting can be booked into.
	 *
	 * <p>Not scoped to a pipeline, and it cannot be: GHL's calendars belong to the location, not
	 * to a pipeline, so there is nothing to narrow by. Reaching this needs the SALES role, which
	 * is the gate — the same shape as the GM's pipeline picker.
	 */
	public List<GhlCalendarClient.CalendarOption> calendars() {
		// Unit 47: the mirror, not GHL. The list is structure — it changes a few times a year — and
		// a booking dialog was calling GHL for it on every open. The shape is unchanged so the form
		// does not know the difference; `REFERENCE_MIRROR` keeps it current, and free SLOTS below
		// are still live because availability is a fact about right now, not about the location.
		return reference.bookableCalendars().stream()
				.map((row) -> new GhlCalendarClient.CalendarOption(row.getGhlId(), row.getName(),
						row.isActive(), row.getSlotMinutes(), row.getTitleTemplate()))
				.toList();
	}

	/**
	 * The calendar's bookable slots, in the timezone the form is showing.
	 *
	 * <p>Not pipeline-scoped, and it cannot be: a calendar belongs to the location. Reaching this
	 * needs the SALES role, which is the gate — the same shape as {@link #calendars()}.
	 */
	public GhlCalendarClient.FreeSlots slots(String calendarId, long fromEpochMs, long toEpochMs,
			String timezone) {
		return slots(calendarId, fromEpochMs, toEpochMs, timezone, null);
	}

	/** The same, narrowed to one team member's availability (Unit 60). Null means the calendar's. */
	public GhlCalendarClient.FreeSlots slots(String calendarId, long fromEpochMs, long toEpochMs,
			String timezone, String ghlUserId) {
		requireText(calendarId, "Which calendar?");
		requireText(timezone, "A slot list needs a timezone");
		if (toEpochMs <= fromEpochMs) {
			throw new InvalidRequestException("The window must end after it starts");
		}
		return calendars.freeSlots(calendarId, fromEpochMs, toEpochMs, timezone, ghlUserId);
	}

	/**
	 * Books a meeting against a deal in the caller's own pipeline.
	 *
	 * @param contactId the deal's contact; GHL hangs the appointment off the contact, not the
	 *                  opportunity, so the opportunity id is carried only for the audit trail
	 */
	public GhlCalendarClient.Meeting book(String opportunityId, String calendarId, String contactId,
			String title, String startTime, String endTime, String description, String assignedUserId,
			String meetingLocationType, String address, boolean customTime, String internalNote) {
		String pipelineId = scope.requireMine(opportunityId);
		requireText(calendarId, "A meeting needs a calendar");
		requireText(contactId, "A meeting needs the deal's contact");
		requireText(title, "A meeting needs a title");

		Instant start = requireInstant(startTime, "start time");
		Instant end = requireInstant(endTime, "end time");
		if (!end.isAfter(start)) {
			throw new InvalidRequestException("A meeting must end after it starts");
		}
		if (start.isBefore(Instant.now())) {
			// GHL would accept it. A meeting in the past is always a typo, and finding out at the
			// point of booking costs nothing.
			throw new InvalidRequestException("That start time is in the past");
		}

		GhlCalendarClient.Meeting booked = calendars.book(calendarId, contactId, opportunityId,
				pipelineId, title, startTime, endTime, description, assignedUserId,
				meetingLocationType, address, customTime);

		// Mirrored from GHL's ANSWER, never from the request: the row records what GHL confirmed,
		// so a field GHL normalised or refused does not end up remembered as what was asked for.
		// Written after the call by design — a row for a meeting that was never booked is worse
		// than no row, and the reverse (a booking GHL accepted that we failed to store) surfaces
		// on the drift report rather than losing the appointment.
		TenantContext caller = TenantContext.current();
		meetings.save(new Meeting(caller.brandId(), booked.id(), opportunityId, contactId,
				calendarId, pipelineId, title, start, end, booked.status(), caller.memberId()));

		// **A failed note does not fail the booking.** GHL hangs a note off an appointment that
		// already exists, so by the time this runs the meeting is in the client's calendar and the
		// invitation has gone. Throwing here would report a success as a failure and invite the
		// salesperson to book it again. Logged rather than swallowed silently, so a note that
		// never landed is findable.
		if (internalNote != null && !internalNote.isBlank()) {
			try {
				calendars.addNote(booked.id(), internalNote.trim());
			}
			catch (RuntimeException noteFailed) {
				log.warn("Meeting {} was booked but its internal note was refused by GHL",
						booked.id(), noteFailed);
			}
		}
		return booked;
	}

	/**
	 * Moves an existing meeting.
	 *
	 * <p>The appointment id used to come only from the booking response, because EvalOS stored
	 * none — "the truth model, not an oversight". <strong>That changed with the meeting mirror:</strong>
	 * the id is now readable from {@code meeting}, so a reschedule no longer requires the caller
	 * to still be holding the response from the call that created it. The GHL write is unchanged
	 * and still authoritative; this method updates the mirror only after GHL accepts.
	 */
	public GhlCalendarClient.Meeting reschedule(String opportunityId, String appointmentId,
			String startTime, String endTime) {
		Owned owned = requireAppointment(opportunityId, appointmentId);

		Instant start = requireInstant(startTime, "start time");
		Instant end = requireInstant(endTime, "end time");
		if (!end.isAfter(start)) {
			throw new InvalidRequestException("A meeting must end after it starts");
		}

		GhlCalendarClient.Meeting moved = calendars.reschedule(appointmentId, opportunityId,
				owned.pipelineId(), startTime, endTime);

		// Only what GHL accepted. The row is now required (Unit 60, spec 60 §1.2): it is how EvalOS
		// knows this appointment is on this deal at all.
		owned.row().movedTo(start, end, moved.status());
		meetings.save(owned.row());
		return moved;
	}

	/**
	 * Cancels a meeting on a deal the caller owns (Unit 60): GHL's own status change, so GHL tells
	 * the client. The mirror keeps GHL's answer as the row's status.
	 */
	public GhlCalendarClient.Meeting cancel(String opportunityId, String appointmentId) {
		Owned owned = requireAppointment(opportunityId, appointmentId);
		GhlCalendarClient.Meeting cancelled = calendars.cancel(appointmentId, opportunityId,
				owned.pipelineId());
		owned.row().syncFromGhl(null, null, null, cancelled.status());
		meetings.save(owned.row());
		return cancelled;
	}

	/** A meeting's internal notes, one GHL page at a time. Read live; EvalOS stores none. */
	public GhlCalendarClient.NotePage notes(String opportunityId, String appointmentId, int offset) {
		requireAppointment(opportunityId, appointmentId);
		return calendars.notes(appointmentId, offset);
	}

	public void addNote(String opportunityId, String appointmentId, String body) {
		requireAppointment(opportunityId, appointmentId);
		calendars.addNote(appointmentId, requireNoteBody(body));
	}

	public void editNote(String opportunityId, String appointmentId, String noteId, String body) {
		requireAppointment(opportunityId, appointmentId);
		requireText(noteId, "Which note?");
		calendars.editNote(appointmentId, noteId, requireNoteBody(body));
	}

	public void deleteNote(String opportunityId, String appointmentId, String noteId) {
		requireAppointment(opportunityId, appointmentId);
		requireText(noteId, "Which note?");
		calendars.deleteNote(appointmentId, noteId);
	}

	// --- blocked time: the caller's own (Unit 60) -------------------------------------------

	/** The caller's blocked time inside a window. */
	public List<GhlCalendarClient.BlockedTime> blockedTime(Instant from, Instant to) {
		if (!to.isAfter(from)) {
			throw new InvalidRequestException("The window must end after it starts");
		}
		return calendars.blockedTimes(myGhlUser(), from.toEpochMilli(), to.toEpochMilli());
	}

	public GhlCalendarClient.BlockedTime block(String title, String startTime, String endTime) {
		Instant start = requireInstant(startTime, "start time");
		Instant end = requireInstant(endTime, "end time");
		if (!end.isAfter(start)) {
			throw new InvalidRequestException("Blocked time must end after it starts");
		}
		return calendars.blockTime(myGhlUser(), title == null ? null : title.trim(), startTime, endTime);
	}

	/**
	 * Removes one of the caller's own blocks.
	 *
	 * <p>GHL's only delete here is the generic event delete, which takes any event id, including
	 * someone else's block or a client's appointment. So the id must be among the caller's own
	 * blocks first.
	 */
	public void unblock(String eventId) {
		requireText(eventId, "Which blocked time?");
		String me = myGhlUser();
		Instant now = Instant.now();
		// ponytail: fixed window (yesterday to a year out). A block outside it cannot be removed
		// here; widen it if anyone ever blocks time further ahead.
		boolean mine = calendars.blockedTimes(me, now.minus(java.time.Duration.ofDays(1)).toEpochMilli(),
				now.plus(java.time.Duration.ofDays(366)).toEpochMilli()).stream()
				.anyMatch((block) -> eventId.equals(block.id()));
		if (!mine) {
			throw new ForbiddenException("That is not one of your blocked times");
		}
		calendars.unblock(eventId);
	}

	/** The appointment's mirror row, only if it is on this deal, and the deal's pipeline. */
	private record Owned(String pipelineId, Meeting row) {
	}

	/**
	 * The caller owns the deal AND the appointment is on it (spec 60 §1.2).
	 *
	 * <p>{@code requireMine} alone only proves the deal is the caller's. An appointment id is
	 * GHL's and says nothing about which deal it hangs off, so without this, any appointment in
	 * the location could be moved or cancelled from a URL naming one of the caller's own deals.
	 * 403, never 404, like every other refusal here.
	 */
	private Owned requireAppointment(String opportunityId, String appointmentId) {
		String pipelineId = scope.requireMine(opportunityId);
		requireText(appointmentId, "Which meeting?");
		Meeting row = meetings.findByBrandIdAndGhlAppointmentId(TenantContext.current().brandId(), appointmentId)
				.filter((held) -> opportunityId.equals(held.getGhlOpportunityId()))
				.orElseThrow(() -> new ForbiddenException("That meeting is not on this deal"));
		return new Owned(pipelineId, row);
	}

	private String myGhlUser() {
		return teamMembers.findById(TenantContext.current().memberId())
				.map(com.ie.evalos.domain.TeamMember::getGhlUserId)
				.filter((id) -> !id.isBlank())
				.orElseThrow(() -> new InvalidRequestException(
						"Your EvalOS account is not linked to a GHL user yet. It links on the next "
								+ "reference sync once your EvalOS email matches your GHL user's email."));
	}

	private static String requireNoteBody(String body) {
		requireText(body, "A note needs some text");
		String trimmed = body.trim();
		if (trimmed.length() > 5000) {
			// GHL's own limit; refused here so the message is readable.
			throw new InvalidRequestException("A note can be at most 5000 characters");
		}
		return trimmed;
	}

	/**
	 * A desk's meetings inside a window, soonest first.
	 *
	 * <p>Read from the mirror rather than from GHL, which is the point of holding it: a diary
	 * screen that made a GHL call per render would spend the location's whole rate budget on the
	 * one screen people leave open.
	 *
	 * <p>Scoped by the caller's own pipeline through the same key every write uses, so a
	 * salesperson sees their desk's meetings and not the brand's.
	 */
	@Transactional(readOnly = true)
	public List<Meeting> diary(Instant from, Instant to) {
		if (!to.isAfter(from)) {
			throw new InvalidRequestException("The window must end after it starts");
		}
		TenantContext caller = TenantContext.current();
		// Every pipeline the caller works — a diary that showed one of three would be a diary
		// somebody misses a meeting from.
		return meetings.findByBrandIdAndGhlPipelineIdInAndStartsAtBetweenOrderByStartsAtAsc(
				caller.brandId(), scope.mine(), from, to);
	}

	/**
	 * One deal's meetings, newest first — for the drawer on a deal card.
	 */
	@Transactional(readOnly = true)
	public List<Meeting> forOpportunity(String opportunityId) {
		// **The DEAL's brand, not the caller's, and that is not a detail.** `requireVisible` lets a
		// GM read any deal, and a GM's principal carries NO brand — so the previous
		// `TenantContext.current().brandId()` would have passed null here and returned an empty
		// diary that looked like a deal with no meetings. Taking the brand off the row that was
		// just authorised is both correct and narrower: it cannot name a brand the check did not
		// already clear.
		return meetings.findByBrandIdAndGhlOpportunityIdOrderByStartsAtDesc(
				scope.requireVisible(opportunityId).getBrandId(), opportunityId);
	}

	private static void requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new InvalidRequestException(message);
		}
	}

	/**
	 * Parses an ISO-8601 instant, refusing anything else.
	 *
	 * <p>Parsed rather than passed through so the refusal is EvalOS's and legible. GHL's own
	 * rejection of a malformed date arrives as a 422 the salesperson cannot act on.
	 */
	private static Instant requireInstant(String value, String what) {
		requireText(value, "A meeting needs a " + what);
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException notADate) {
			throw new InvalidRequestException(
					"The " + what + " must be an ISO-8601 instant such as 2026-09-15T14:00:00Z");
		}
	}
}
