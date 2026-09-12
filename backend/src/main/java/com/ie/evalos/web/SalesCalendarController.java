package com.ie.evalos.web;

import java.util.List;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.SalesMeetingService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

	SalesCalendarController(SalesMeetingService meetings) {
		this.meetings = meetings;
	}

	@GetMapping("/calendars")
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<List<SalesMeetingService.Calendar>> calendars() {
		return ApiResponse.ok(meetings.calendars());
	}
}
