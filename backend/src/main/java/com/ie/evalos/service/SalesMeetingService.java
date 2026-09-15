package com.ie.evalos.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Meeting;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.repository.MeetingRepository;
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
	private final PipelineScope scope;
	private final MeetingRepository meetings;

	SalesMeetingService(GhlCalendarClient calendars, PipelineScope scope,
			MeetingRepository meetings) {
		this.calendars = calendars;
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
		return calendars.calendars();
	}

	/**
	 * The calendar's bookable slots, in the timezone the form is showing.
	 *
	 * <p>Not pipeline-scoped, and it cannot be: a calendar belongs to the location. Reaching this
	 * needs the SALES role, which is the gate — the same shape as {@link #calendars()}.
	 */
	public GhlCalendarClient.FreeSlots slots(String calendarId, long fromEpochMs, long toEpochMs,
			String timezone) {
		requireText(calendarId, "Which calendar?");
		requireText(timezone, "A slot list needs a timezone");
		if (toEpochMs <= fromEpochMs) {
			throw new InvalidRequestException("The window must end after it starts");
		}
		return calendars.freeSlots(calendarId, fromEpochMs, toEpochMs, timezone);
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
		String pipelineId = scope.requireMine(opportunityId);
		requireText(appointmentId, "Which meeting?");

		Instant start = requireInstant(startTime, "start time");
		Instant end = requireInstant(endTime, "end time");
		if (!end.isAfter(start)) {
			throw new InvalidRequestException("A meeting must end after it starts");
		}

		GhlCalendarClient.Meeting moved = calendars.reschedule(appointmentId, opportunityId,
				pipelineId, startTime, endTime);

		// Update only what GHL accepted, and only if we hold the row. A meeting booked before the
		// mirror existed has no row: `findBy...` is empty, nothing is written, and the reschedule
		// still succeeds in GHL. Failing the call because EvalOS lacks a copy would make the
		// mirror load-bearing, which it is explicitly not.
		TenantContext caller = TenantContext.current();
		meetings.findByBrandIdAndGhlAppointmentId(caller.brandId(), appointmentId)
				.ifPresent((row) -> {
					row.movedTo(start, end, moved.status());
					meetings.save(row);
				});
		return moved;
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
		scope.requireMine(opportunityId);
		return meetings.findByBrandIdAndGhlOpportunityIdOrderByStartsAtDesc(
				TenantContext.current().brandId(), opportunityId);
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
