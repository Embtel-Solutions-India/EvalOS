package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Meeting;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.integration.GhlCustomFieldClient;
import com.ie.evalos.integration.GhlUserClient;
import com.ie.evalos.service.SalesDeskService;
import com.ie.evalos.service.SalesMeetingService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calendar list a salesperson picks from when booking a meeting.
 *
 * <p><strong>Its own controller rather than a method on {@code SalesDeskController}</strong>,
 * which is mapped at {@code /api/sales/opportunities/{opportunityId}} — every route on it acts on
 * one deal. A calendar list belongs to the location, not to a deal, so hanging it off an
 * opportunity id would invent a relationship GHL does not have and force the client to pass a
 * deal it is not asking about. Same split, and the same reasoning, as
 * {@link GhlPipelineController}.
 *
 * <p><strong>SALES, not GM.</strong> The pipeline picker is GM-only because assigning a pipeline
 * changes what a person may see. Picking a calendar changes nothing — it is the salesperson's own
 * booking, and the deal it is booked against is already scoped to their pipeline by
 * {@code SalesMeetingService}.
 *
 * <p>Thin (invariant 12): no decision here.
 */
@RestController
@RequestMapping("/api/sales")
public class SalesCalendarController {

	private final SalesMeetingService meetings;
	private final GhlCustomFieldClient customFields;
	private final GhlUserClient users;
	private final SalesDeskService desk;

	SalesCalendarController(SalesMeetingService meetings, GhlCustomFieldClient customFields,
			GhlUserClient users, SalesDeskService desk) {
		this.meetings = meetings;
		this.customFields = customFields;
		this.users = users;
		this.desk = desk;
	}

	/**
	 * The desk's open follow-ups due before a cutoff, soonest first.
	 *
	 * <p>Read from EvalOS's mirror. GHL lists tasks only per contact, so a desk-wide view over GHL
	 * would be one call per contact — which the 100-per-10s budget and the screen's patience both
	 * refuse.
	 *
	 * @param before ISO-8601 instant; everything due before it that is still open
	 */
	@GetMapping("/follow-ups")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<FollowUpView>> followUps(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
		return ApiResponse.ok(desk.openFollowUps(before).stream().map(FollowUpView::of).toList());
	}

	/**
	 * What a desk is shown about a follow-up.
	 *
	 * <p>A projection rather than the entity: the row carries {@code brandId}, {@code setBy} and
	 * {@code syncedAt}, which are EvalOS bookkeeping. {@code taskId} is exposed because the
	 * complete route takes it.
	 */
	public record FollowUpView(String taskId, String opportunityId, String contactId, String title,
			String note, Instant dueAt, boolean completed) {

		static FollowUpView of(com.ie.evalos.domain.FollowUp row) {
			return new FollowUpView(row.getGhlTaskId(), row.getGhlOpportunityId(),
					row.getGhlContactId(), row.getTitle(), row.getBody(), row.getDueAt(),
					row.isCompleted());
		}
	}

	/**
	 * The location's users, for the booking dialog's "Team member" picker.
	 *
	 * <p>SALES, like the calendar list: a colleague's name and work address is not client data,
	 * and the salesperson is choosing who takes the call.
	 */
	@GetMapping("/users")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<GhlUserClient.User>> ghlUsers() {
		return ApiResponse.ok(users.inLocation());
	}

	/**
	 * The custom fields this location puts on an opportunity, so the "add opportunity" form can
	 * draw the same questions GHL's own form asks.
	 *
	 * <p><strong>Read live, not mirrored.</strong> The ids are location-scoped and the sub-account
	 * swap on 2026-09-11 proved what happens to a hardcoded set of GHL ids. Fetching them means a
	 * field renamed, added or re-optioned in GHL shows up on the form with no deploy.
	 *
	 * <p><strong>SALES, like the calendar list.</strong> These are definitions, not data: knowing
	 * that a location has a "Visa Category" field discloses nothing about any client.
	 */
	@GetMapping("/opportunity-fields")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<GhlCustomFieldClient.CustomField>> opportunityFields() {
		return ApiResponse.ok(customFields.forOpportunities());
	}

	@GetMapping("/calendars")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<GhlCalendarClient.CalendarOption>> calendars() {
		return ApiResponse.ok(meetings.calendars());
	}

	/**
	 * A calendar's free slots, which is what GHL's booking dialog is actually driven by.
	 *
	 * <p>Availability is GHL's to compute — open hours, buffers, per-day caps, the team member's
	 * other appointments, minimum notice. EvalOS asking rather than calculating is the only way
	 * the two agree.
	 *
	 * @param from     epoch milliseconds, inclusive
	 * @param to       epoch milliseconds, exclusive
	 * @param timezone IANA zone; GHL renders the slots in it and the form shows which one
	 */
	@GetMapping("/calendars/{calendarId}/slots")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<GhlCalendarClient.FreeSlots> slots(@PathVariable String calendarId,
			@RequestParam long from, @RequestParam long to, @RequestParam String timezone) {
		return ApiResponse.ok(meetings.slots(calendarId, from, to, timezone));
	}

	/**
	 * The caller's diary: meetings on their own pipeline inside a window, soonest first.
	 *
	 * <p><strong>Read from EvalOS's mirror, not from GHL.</strong> A diary is the screen people
	 * leave open; a GHL call per render would spend the location's 100-per-10s budget on it. The
	 * mirror is written from GHL's booking response, so it holds what GHL confirmed — see
	 * {@code V47__meeting.sql} for why a mirror rather than a read-back.
	 *
	 * <p><strong>The window is required and unbounded windows are refused by the service</strong>,
	 * not clamped here: a caller asking for a decade is asking a question this screen does not
	 * answer, and silently narrowing it would show them a subset they would read as the whole.
	 *
	 * @param from inclusive ISO-8601 instant
	 * @param to   exclusive ISO-8601 instant; must be after {@code from}
	 */
	@GetMapping("/meetings")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<MeetingView>> diary(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return ApiResponse.ok(meetings.diary(from, to).stream().map(MeetingView::of).toList());
	}

	/**
	 * What a desk is shown about a meeting.
	 *
	 * <p>A projection rather than the entity, for the reason every other route here has one: the
	 * row carries {@code brandId}, {@code bookedBy} and {@code syncedAt}, which are EvalOS
	 * bookkeeping and not a salesperson's business. {@code appointmentId} is exposed because the
	 * reschedule route takes it.
	 */
	public record MeetingView(String appointmentId, String opportunityId, String contactId,
			String title, Instant startsAt, Instant endsAt, String status) {

		static MeetingView of(Meeting row) {
			return new MeetingView(row.getGhlAppointmentId(), row.getGhlOpportunityId(),
					row.getGhlContactId(), row.getTitle(), row.getStartsAt(), row.getEndsAt(),
					row.getStatus());
		}
	}
}
