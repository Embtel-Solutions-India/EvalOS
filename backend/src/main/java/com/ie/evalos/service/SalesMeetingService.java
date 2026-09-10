package com.ie.evalos.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.integration.GhlCalendarClient;

import org.springframework.stereotype.Service;

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
 * for appointments, so a double-submit books two meetings. That is a real limit and it is stated
 * rather than papered over: the fix would be EvalOS holding an appointment row, which is exactly
 * the local mirror the programme's truth model refuses.
 */
@Service
public class SalesMeetingService {

	/** What the desk shows in the calendar picker. */
	public record Calendar(String id, String name) {
	}

	private final GhlCalendarClient calendars;
	private final PipelineScope scope;

	SalesMeetingService(GhlCalendarClient calendars, PipelineScope scope) {
		this.calendars = calendars;
		this.scope = scope;
	}

	/**
	 * The calendars a meeting can be booked into.
	 *
	 * <p>Not scoped to a pipeline, and it cannot be: GHL's calendars belong to the location, not
	 * to a pipeline, so there is nothing to narrow by. Reaching this needs the SALES role, which
	 * is the gate — the same shape as the GM's pipeline picker.
	 */
	public List<Calendar> calendars() {
		return calendars.calendars().stream()
				.map((option) -> new Calendar(option.id(), option.name()))
				.toList();
	}

	/**
	 * Books a meeting against a deal in the caller's own pipeline.
	 *
	 * @param contactId the deal's contact; GHL hangs the appointment off the contact, not the
	 *                  opportunity, so the opportunity id is carried only for the audit trail
	 */
	public GhlCalendarClient.Meeting book(String opportunityId, String calendarId, String contactId,
			String title, String startTime, String endTime) {
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

		return calendars.book(calendarId, contactId, opportunityId, pipelineId, title,
				startTime, endTime);
	}

	/**
	 * Moves an existing meeting.
	 *
	 * <p>The appointment id comes from the booking response; EvalOS stores none, so the caller
	 * is working from something it has just been handed. That is the truth model, not an
	 * oversight — see {@link GhlCalendarClient}.
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

		return calendars.reschedule(appointmentId, opportunityId, pipelineId, startTime, endTime);
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
