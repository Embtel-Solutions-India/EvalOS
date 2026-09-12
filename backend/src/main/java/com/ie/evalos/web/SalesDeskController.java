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

	public record FollowUpRequest(@NotBlank String contactId, @NotBlank String title,
			@NotBlank String dueAt) {
	}

	/** ISO-8601 instants. The service refuses a past start and an end that is not after it. */
	public record BookMeetingRequest(@NotBlank String calendarId, @NotBlank String contactId,
			@NotBlank String title, @NotBlank String startTime, @NotBlank String endTime) {
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
				request.title(), request.dueAt())));
	}

	/**
	 * Books a meeting with the deal's contact.
	 *
	 * <p><strong>Booking runs GHL's automations</strong>, which is how the client actually
	 * receives the invitation — EvalOS still sends nothing itself (invariant 14). Pressing this
	 * twice books two meetings: GHL offers no upsert for appointments, so there is no
	 * idempotency to lean on, and the honest answer is to say so rather than to mirror the
	 * appointment locally.
	 */
	@PostMapping("/meetings")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<GhlCalendarClient.Meeting> bookMeeting(@PathVariable String opportunityId,
			@RequestBody @Valid BookMeetingRequest request) {
		return ApiResponse.ok(meetings.book(opportunityId, request.calendarId(), request.contactId(),
				request.title(), request.startTime(), request.endTime()));
	}

	@PutMapping("/meetings/{appointmentId}")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<GhlCalendarClient.Meeting> rescheduleMeeting(@PathVariable String opportunityId,
			@PathVariable String appointmentId, @RequestBody @Valid RescheduleMeetingRequest request) {
		return ApiResponse.ok(meetings.reschedule(opportunityId, appointmentId, request.startTime(),
				request.endTime()));
	}
}
