package com.ie.evalos.web;

import java.math.BigDecimal;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.service.SalesDeskService;
import com.ie.evalos.service.SalesMeetingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sales desk's actions on a deal in the caller's own pipeline.
 *
 * <p><strong>No route names a pipeline.</strong> The path variable is an <em>opportunity</em>,
 * and it is checked against the caller's pipeline before anything happens — the same rule Unit
 * 34c's document filter and Unit 35's case routes follow, and the reason a path variable is safe
 * here at all.
 *
 * <p><strong>There is no route to move a deal to another pipeline.</strong> That is the
 * marketing-to-sales promotion and it belongs to GHL's workflow. A button here would be a second
 * promotion path racing automation the business already owns.
 *
 * <p><strong>Meetings landed 2026-09-11</strong>, as a follow-on rather than with the rest of the
 * desk. They were held back on "the calendar scopes are not granted" — which turned out to be
 * false for the two read scopes, and untested for the write one. {@code calendars/events.write}
 * is still unverified, so a booking may answer 502 naming that scope; everything else here works
 * on grants that were already in place. A follow-up remains a GHL <em>task</em>
 * ({@code contacts.write}), which is a different thing from a meeting and always was.
 */
@RestController
@RequestMapping("/api/sales/opportunities/{opportunityId}")
public class SalesDeskController {

	/** Rename, re-price, or both. The service refuses "neither". */
	public record UpdateDealRequest(String name, BigDecimal monetaryValue) {
	}

	public record MoveStageRequest(@NotBlank String stageId) {
	}

	/** {@code won}, {@code lost} or {@code abandoned}. The service refuses anything else. */
	public record CloseDealRequest(@NotBlank String status) {
	}

	/**
	 * A follow-up: a GHL task on the deal's contact.
	 *
	 * @param note           GHL's task {@code body}. Optional — the reminder's title is what shows
	 *                       in a list, and the note is what the salesperson wants to remember.
	 * @param assignedUserId absent means GHL's own default assignment. A guess would put the
	 *                       reminder on the wrong person's list.
	 */
	public record FollowUpRequest(@NotBlank String contactId, @NotBlank String title,
			@NotBlank String dueAt, String note, String assignedUserId) {
	}

	/** ISO-8601 instants. The service refuses a past start and an end that is not after it. */
	/**
	 * The booking form, matched field-for-field against GHL's own "Book appointment" dialog.
	 *
	 * <p><strong>{@code internalNote} is a second GHL call, not a field.</strong> The appointment
	 * body carries no note; GHL hangs one off an appointment that already exists. It is on this
	 * request anyway because a salesperson types it in the same dialog, and splitting it into a
	 * second round trip from the browser would let the meeting succeed while the note silently
	 * never got sent.
	 *
	 * <p><strong>Absent because GHL's API has no such field:</strong> <em>Add guests</em> — the
	 * appointment body carries no guest list. <em>Blocked off time</em> is the dialog's other tab
	 * and a different endpoint entirely; it books nobody and belongs with availability, not here.
	 *
	 * @param customTime GHL's <em>Default | Custom</em> toggle. Default books into a slot the
	 *                   calendar says is free; Custom sets {@code ignoreFreeSlotValidation} and
	 *                   takes any time. Off unless the salesperson chooses it, because GHL's
	 *                   refusal of a colliding slot is a visible failure and overriding it turns a
	 *                   double-booking into a silent success.
	 * @param assignedUserId null means <em>Calendar Default</em> — the calendar's own assigned
	 *                   member takes it, which is what GHL's picker shows first.
	 */
	public record BookMeetingRequest(@NotBlank String calendarId, @NotBlank String contactId,
			@NotBlank String title, @NotBlank String startTime, @NotBlank String endTime,
			String description, String assignedUserId, String meetingLocationType, String address,
			boolean customTime, String internalNote) {
	}

	public record RescheduleMeetingRequest(@NotBlank String startTime, @NotBlank String endTime) {
	}

	private final SalesDeskService desk;
	private final SalesMeetingService meetings;

	SalesDeskController(SalesDeskService desk, SalesMeetingService meetings) {
		this.desk = desk;
		this.meetings = meetings;
	}

	@PutMapping
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<SalesDeskService.Deal> update(@PathVariable String opportunityId,
			@RequestBody @Valid UpdateDealRequest request) {
		return ApiResponse.ok(desk.update(opportunityId, request.name(), request.monetaryValue()));
	}

	@PutMapping("/stage")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<SalesDeskService.Deal> moveStage(@PathVariable String opportunityId,
			@RequestBody @Valid MoveStageRequest request) {
		return ApiResponse.ok(desk.moveToStage(opportunityId, request.stageId()));
	}

	/**
	 * Closes the deal.
	 *
	 * <p><strong>Winning returns before the case exists.</strong> EvalOS tells GHL, and GHL's
	 * webhook creates the case (invariant 8, Handoff A). The screen must show that as pending
	 * rather than as nothing — the alternative is a salesperson pressing Won twice because the
	 * first press appeared to do nothing.
	 */
	@PutMapping("/status")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<SalesDeskService.Deal> close(@PathVariable String opportunityId,
			@RequestBody @Valid CloseDealRequest request) {
		return ApiResponse.ok(desk.close(opportunityId, request.status()));
	}

	/** The GHL task id, so the caller can say what was created rather than just "done". */
	public record FollowUpCreated(String ghlTaskId) {
	}

	@PostMapping("/follow-ups")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<FollowUpCreated> followUp(@PathVariable String opportunityId,
			@RequestBody @Valid FollowUpRequest request) {
		return ApiResponse.ok(new FollowUpCreated(desk.followUp(opportunityId, request.contactId(),
				request.title(), request.dueAt(), request.note(), request.assignedUserId())));
	}

	/**
	 * Books a meeting with the deal's contact.
	 *
	 * <p><strong>Booking runs GHL's automations</strong>, which is how the client actually
	 * receives the invitation — EvalOS still sends nothing itself (invariant 14). Pressing this
	 * twice books two meetings: GHL offers no upsert for appointments, so there is still no
	 * idempotency to lean on.
	 *
	 * <p><strong>The appointment IS mirrored locally now</strong>, which reverses the second half
	 * of what this note used to say ("the honest answer is to say so rather than to mirror the
	 * appointment locally"). The mirror does not fix the double-submit — two bookings in GHL
	 * become two rows — but it is what lets any screen show the meeting afterwards, and it makes
	 * the duplicate visible instead of invisible. See {@code V47__meeting.sql}.
	 */
	@PostMapping("/meetings")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<GhlCalendarClient.Meeting> bookMeeting(@PathVariable String opportunityId,
			@RequestBody @Valid BookMeetingRequest request) {
		return ApiResponse.ok(meetings.book(opportunityId, request.calendarId(), request.contactId(),
				request.title(), request.startTime(), request.endTime(), request.description(),
				request.assignedUserId(), request.meetingLocationType(), request.address(),
				request.customTime(), request.internalNote()));
	}

	/**
	 * Marks a follow-up done.
	 *
	 * <p>GHL first, then the mirror — so a refusal leaves nothing locally claiming to be done. The
	 * task id comes from the follow-up list, which reads EvalOS's own rows.
	 */
	@PutMapping("/follow-ups/{taskId}/complete")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<Void> completeFollowUp(@PathVariable String opportunityId,
			@PathVariable String taskId) {
		desk.completeFollowUp(opportunityId, taskId);
		return ApiResponse.ok(null);
	}

	@PutMapping("/meetings/{appointmentId}")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<GhlCalendarClient.Meeting> rescheduleMeeting(@PathVariable String opportunityId,
			@PathVariable String appointmentId, @RequestBody @Valid RescheduleMeetingRequest request) {
		return ApiResponse.ok(meetings.reschedule(opportunityId, appointmentId, request.startTime(),
				request.endTime()));
	}
}
