package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.CaseTimelineService;
import com.ie.evalos.service.CaseTimelineService.TimelineEntry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The case's history, oldest first.
 *
 * <p>No {@code @PreAuthorize}: every staff role that can open a case can read what happened
 * to it, and the scoped load inside the service is what decides whether they can open it —
 * an out-of-scope case answers 403 there, before any audit row is fetched. A role gate here
 * would be the wrong tool, the same reasoning as the notification centre's routes.
 *
 * <p>Read-only, and there is no write counterpart anywhere: the trail is append-only
 * (invariant 13) and {@code AuditEventRepository} has no method that could change it.
 */
@RestController
@RequestMapping("/api/cases/{id}/timeline")
public class CaseTimelineController {

	private final CaseTimelineService timeline;

	CaseTimelineController(CaseTimelineService timeline) {
		this.timeline = timeline;
	}

	@GetMapping
	public ApiResponse<List<TimelineEntry>> timeline(@PathVariable UUID id) {
		return ApiResponse.ok(timeline.forCase(id));
	}
}
