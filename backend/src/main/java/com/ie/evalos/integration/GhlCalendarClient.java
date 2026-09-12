package com.ie.evalos.integration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.AuditService;

import org.springframework.stereotype.Component;

/**
 * Meetings, booked into GHL's calendars.
 *
 * <p><strong>GHL owns the calendar, exactly as it owns the pipeline.</strong> EvalOS holds no
 * appointment row and no availability model. The programme's truth rule (`00b` §1.3) applies
 * unchanged: the meeting lives over there, EvalOS is the desk it is booked from. A local mirror
 * would be a second answer to "when is the call", and the two would disagree the first time
 * somebody moved it in GHL.
 *
 * <p><strong>Separate from {@link GhlWriteClient}, which handles contacts and opportunities.</strong>
 * That class is the deal; this one is the diary. Splitting them keeps each small enough to read,
 * and matches the split already made between {@link GhlOpportunityClient} (reads boards) and the
 * write client.
 *
 * <p><strong>It audits, because it holds a {@code GhlHttp} and calls a write verb.</strong>
 * {@code GhlHttpTest}'s structural check enforces that on every such class; this one satisfies it
 * rather than waiving it, for the reason Unit 37 gave — the door is transport and cannot know
 * what a write <em>means</em>, but a method called {@code book} can.
 *
 * <p><strong>⚠ {@code calendars/events.write} is the one grant in this programme still
 * unverified.</strong> The two read scopes here were probed live on 2026-09-11 and both answer
 * 200; the write scope cannot be probed without putting a real appointment on a live calendar,
 * so it has not been. If it turns out to be missing, {@link #missingScopeHint} names it rather
 * than leaving a bare 502 — the same diagnostic {@link GhlInvoiceClient} carries, and for the
 * same reason: every other GHL call in EvalOS works on this token, so a 401 here reads as
 * "the token is broken" when it means "one scope is missing".
 */
@Component
public class GhlCalendarClient {

	/** The scope booking needs, named once so the diagnostic cannot drift from it. */
	public static final String WRITE_SCOPE = "calendars/events.write";

	/** The scope listing calendars needs. Verified granted 2026-09-11. */
	public static final String READ_SCOPE = "calendars.readonly";

	/**
	 * Reading a contact's appointments needs {@code contacts.readonly}, not a calendar scope.
	 *
	 * <p>GHL hangs that read off {@code /contacts/{id}/appointments}, which is the endpoint the
	 * Client Portal wants: it is keyed on the contact, which is exactly what a party-scoped
	 * portal credential holds. Verified granted 2026-09-11.
	 */
	public static final String CONTACT_READ_SCOPE = "contacts.readonly";

	/** A calendar a meeting can be booked into: a label and the id the booking needs. */
	public record CalendarOption(String id, String name) {
	}

	/**
	 * A booked meeting, narrowed to what a desk and a client portal need.
	 *
	 * <p>GHL's appointment payload carries assignment, recurrence, location configuration and
	 * more. Neither screen shows any of it, and the narrowing is the same discipline every other
	 * client here applies.
	 */
	public record Meeting(String id, String calendarId, String contactId, String title,
			String startTime, String endTime, String status) {
	}

	/**
	 * One meeting, narrowed to what a <em>client</em> is owed sight of.
	 *
	 * <p>The narrowing is the point and it drops more than it keeps. GHL's event carries
	 * {@code assignedUserId} (an internal staff id), {@code notes} (written by staff, for
	 * staff — the same reason `opportunity_note` never reaches a client) and
	 * {@code appointmentMeta.defaultFormDetails} with the contact's own email and phone. None
	 * of it belongs on a portal page.
	 *
	 * <p>{@code location} is GHL's {@code address}, which is a join link for an online meeting
	 * and a street address for one in person. Named for what it is rather than assumed to be a
	 * URL, because the portal has to render both.
	 *
	 * <p><strong>{@code startsAt} and {@code endsAt} are passed through as GHL's own strings and
	 * are NOT ISO-8601.</strong> See {@link #forContact}.
	 */
	public record ClientMeeting(String id, String title, String startsAt, String endsAt,
			String status, String location) {
	}

	private final GhlHttp http;
	private final AuditService audit;

	GhlCalendarClient(GhlHttp http, AuditService audit) {
		this.http = http;
		this.audit = audit;
	}

	/**
	 * The location's calendars, for the picker.
	 *
	 * <p>Id and name only — the same decision {@code GhlPipelineController} made about pipelines,
	 * and for the same reason: a picker needs a label and a value, and carrying GHL's full
	 * calendar configuration would be the beginning of EvalOS holding a view of it.
	 */
	public List<CalendarOption> calendars() {
		try {
			CalendarListResponse response = http.get(CalendarListResponse.class,
					(uri) -> uri.path("/calendars/")
							.queryParam("locationId", http.locationId())
							.build());
			return Optional.ofNullable(response == null ? null : response.calendars()).orElse(List.of())
					.stream()
					.map((row) -> new CalendarOption(row.id(), row.name()))
					.toList();
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused, READ_SCOPE);
		}
	}

	/**
	 * Books a meeting with one contact.
	 *
	 * <p><strong>{@code toNotify} is left at GHL's default of true, deliberately.</strong> That
	 * is what runs GHL's automations, which is how the contact actually receives the invitation —
	 * and it is the right side of invariant 14 rather than a breach of it: EvalOS still sends
	 * nothing, GHL sends everything. Suppressing it would book a meeting the client never hears
	 * about, which is the failure mode gap G15 already describes for expert links.
	 *
	 * <p><strong>Slot validation is left on, also deliberately.</strong> GHL refuses a booking
	 * that collides or falls outside the calendar's availability, and that refusal surfaces as a
	 * 4xx the salesperson can read. {@code ignoreFreeSlotValidation} would turn a double-booking
	 * into a silent success, which is worse than a visible refusal.
	 *
	 * <p><strong>No idempotency key, and GHL offers none for this endpoint.</strong> Unlike
	 * contacts and opportunities there is no upsert to fall back on, so a double-submit books two
	 * meetings. That is why {@code SalesMeetingService} refuses a booking with no end time and
	 * why nothing retries — the same rule Unit 37 §7 set for every write.
	 *
	 * @param calendarId which calendar; from {@link #calendars()}
	 * @param contactId  the deal's contact — GHL hangs the appointment off the contact
	 * @param startTime  ISO-8601 with an offset; GHL refuses what it cannot parse
	 * @param endTime    ISO-8601; optional to GHL, required by the service above
	 * @throws GhlUnavailableException if GHL is not configured, refused, or lacks
	 *                                 {@link #WRITE_SCOPE}
	 */
	public Meeting book(String calendarId, String contactId, String opportunityId, String pipelineId,
			String title, String startTime, String endTime) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("calendarId", calendarId);
		body.put("contactId", contactId);
		body.put("startTime", startTime);
		putIfPresent(body, "endTime", endTime);
		putIfPresent(body, "title", title);
		// `confirmed` rather than `new`: a salesperson booking from the desk has already agreed
		// the time with the client. `new` would leave it looking unconfirmed in GHL to everyone
		// who did not make the call.
		body.put("appointmentStatus", "confirmed");

		AppointmentRow row;
		try {
			row = http.post(AppointmentRow.class,
					(uri) -> uri.path("/calendars/events/appointments").build(), body);
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused, WRITE_SCOPE);
		}
		AppointmentRow booked = require(row);

		// Audited against the OPPORTUNITY, not the appointment: "this deal had a meeting booked"
		// is the sentence a salesperson or a reviewer wants out of the trail, and an appointment
		// id nobody holds elsewhere would be an object nothing else in the history mentions.
		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId),
				AuditAction.CHASED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId,
						"ghlAppointmentId", booked.id(), "calendarId", calendarId,
						"startTime", String.valueOf(booked.startTime())));

		return toMeeting(booked);
	}

	/**
	 * Moves an existing meeting.
	 *
	 * <p>A separate verb rather than a flag on {@link #book}, because the two are different acts
	 * with different audit meanings — and because GHL exposes them as different endpoints. The
	 * appointment id is GHL's; EvalOS stores none, so a caller has to have just read it.
	 */
	public Meeting reschedule(String appointmentId, String opportunityId, String pipelineId,
			String startTime, String endTime) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("startTime", startTime);
		putIfPresent(body, "endTime", endTime);

		AppointmentRow row;
		try {
			row = http.put(AppointmentRow.class,
					(uri) -> uri.path("/calendars/events/appointments/{id}").build(appointmentId), body);
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused, WRITE_SCOPE);
		}
		AppointmentRow moved = require(row);

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId),
				AuditAction.CHASED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId,
						"ghlAppointmentId", appointmentId, "rescheduledTo", String.valueOf(moved.startTime())));

		return toMeeting(moved);
	}

	/**
	 * Every meeting GHL holds for one contact, for the Client Portal.
	 *
	 * <p><strong>Three things about GHL's payload that are traps, all pinned by tests.</strong>
	 *
	 * <ol>
	 * <li><strong>The times are not ISO-8601.</strong> GHL returns {@code "2026-09-13 12:30:00"}
	 * — a space instead of a {@code T}, and <em>no offset at all</em>. {@code Instant.parse}
	 * throws on it. They are passed through as strings rather than parsed into a wrong instant:
	 * with no zone in the payload, any parse would be inventing one, and inventing UTC would
	 * show a Pacific client a meeting seven hours out. The same endpoint's <em>write</em> side
	 * takes proper ISO with an offset, which is how easy it is to assume symmetry here.</li>
	 * <li><strong>{@code deleted} must be honoured.</strong> A removed appointment still comes
	 * back in the list, and showing a client a meeting that is not happening is worse than
	 * showing none.</li>
	 * <li><strong>GHL sends the status twice</strong>, as {@code appointmentStatus} and as
	 * {@code appoinmentStatus} — its own typo, present in the live payload. The correctly
	 * spelled one is read; the misspelling is ignored rather than used as a fallback, because a
	 * fallback onto a typo is a dependency on GHL never fixing it.</li>
	 * </ol>
	 *
	 * <p><strong>{@code contactId} never comes from a request.</strong> The caller passes the id
	 * off the portal credential — the same access model, word for word, as
	 * {@code GhlInvoiceClient.forContact}.
	 */
	public List<ClientMeeting> forContact(String contactId) {
		try {
			ContactEventsResponse response = http.get(ContactEventsResponse.class,
					(uri) -> uri.path("/contacts/{contactId}/appointments").build(contactId));

			return Optional.ofNullable(response == null ? null : response.events()).orElse(List.of())
					.stream()
					.filter((event) -> !Boolean.TRUE.equals(event.deleted()))
					.map((event) -> new ClientMeeting(event.id(), event.title(), event.startTime(),
							event.endTime(), event.appointmentStatus(), event.address()))
					.toList();
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused, CONTACT_READ_SCOPE);
		}
	}

	// --- diagnostics and helpers -----------------------------------------------------

	/**
	 * Re-throws a refusal with the likely missing grant named.
	 *
	 * <p>Only 401 and 403 are decorated, for the reason {@link GhlInvoiceClient} gives: a guard
	 * that also fired on 404s and timeouts would teach the reader to ignore it.
	 */
	private static GhlUnavailableException missingScopeHint(GhlUnavailableException refused, String scope) {
		String message = refused.getMessage() == null ? "" : refused.getMessage();
		if (!message.contains("401") && !message.contains("403")) {
			return refused;
		}
		return new GhlUnavailableException(message + " — the calendar API needs the " + scope
				+ " scope. The read scopes were verified granted on 2026-09-11 and " + WRITE_SCOPE
				+ " was never probed, so check the token's grant before suspecting the token.",
				refused);
	}

	private static AppointmentRow require(AppointmentRow row) {
		if (row == null || row.id() == null) {
			// GHL answered without the one field that makes the response usable. Louder than a
			// null return, which would surface later as an unexplained empty meeting.
			throw new GhlUnavailableException("GHL returned no appointment id");
		}
		return row;
	}

	private static Meeting toMeeting(AppointmentRow row) {
		return new Meeting(row.id(), row.calendarId(), row.contactId(), row.title(),
				row.startTime(), row.endTime(), row.appointmentStatus());
	}

	/** The staff member responsible, or null outside a request — the audit contract's own rule. */
	private static UUID actor() {
		return TenantContext.find().map(TenantContext::memberId).orElse(null);
	}

	/**
	 * The same derived key {@code GhlWriteClient} uses, and deliberately identical.
	 *
	 * <p>A meeting booked on a deal belongs in that deal's history beside its stage moves and its
	 * follow-ups. Computing the key the same way is what puts it there — so this duplicates two
	 * lines rather than the two classes sharing a helper, which is the smaller cost than a
	 * utility class holding one method. If a third client needs it, that is the moment to extract.
	 */
	private static UUID auditKey(String objectType, String ghlId) {
		return UUID.nameUUIDFromBytes((objectType + ':' + ghlId).getBytes(StandardCharsets.UTF_8));
	}

	private static void putIfPresent(Map<String, Object> body, String key, String value) {
		if (value != null && !value.isBlank()) {
			body.put(key, value);
		}
	}

	// --- wire shapes -----------------------------------------------------------------

	record CalendarListResponse(List<CalendarRow> calendars) {
	}

	record ContactEventsResponse(List<ContactEventRow> events) {
	}

	/**
	 * Deliberately a subset of the fields GHL sends. Jackson ignores the rest, which is what
	 * keeps {@code assignedUserId}, {@code notes} and the contact's own details from ever
	 * reaching a record this codebase could accidentally serialise.
	 */
	record ContactEventRow(String id, String title, String startTime, String endTime,
			String appointmentStatus, String address, Boolean deleted) {
	}

	record CalendarRow(String id, String name) {
	}

	/**
	 * GHL returns the appointment's fields at the top level of the response, not wrapped.
	 * Pinned by {@code GhlCalendarClientHttpTest} — a wrapper assumed and not checked is the
	 * mistake that cost this codebase an afternoon on {@code pipeline_stage_id}.
	 */
	record AppointmentRow(String id, String calendarId, String contactId, String title,
			String startTime, String endTime, String appointmentStatus) {
	}
}
