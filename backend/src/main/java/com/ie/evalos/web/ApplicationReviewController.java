package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ClientApplicationService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the client asked for, read by the staff who act on it (Unit 43 §6c).
 *
 * <p><strong>Required by the flow, not an extra.</strong> "Sales reviews the answers and contacts
 * the client" is a step in the middle of the funnel, and a review step with nowhere to read the
 * thing being reviewed does not exist.
 *
 * <p><strong>Sales AND Production, which is `43` §6c's decision.</strong> Sales prices the work
 * from the answers; the PM and the expert then do the work described in them, and re-interviewing
 * a client who has already typed their history is the failure this whole unit exists to prevent.
 * The Expert Network Manager is absent because they staff a case rather than read one, and the
 * expert reaches the answers through their own portal or not at all — a role list is not the
 * place to widen the expert surface by accident.
 *
 * <p><strong>Brand-scoped and deliberately NOT pipeline-scoped.</strong> Every read here filters
 * on the caller's brand, which is the boundary that matters. Adding
 * {@code PipelineScope.requireMine} on top would refuse Production outright — they hold no
 * pipeline at all — and would buy nothing against Sales, since a colleague on another sales
 * pipeline in the same brand seeing an intake questionnaire is not a leak, it is a handover.
 *
 * <p>Its own controller rather than a route on {@code SalesDeskController}, which is
 * {@code hasRole('SALES')} throughout and mapped as the *desk*: a Project Manager reaching an
 * application through a class called the sales desk would be the kind of thing that gets
 * "tidied" back to SALES-only by the next person reading it.
 */
@RestController
@RequestMapping("/api/opportunities/{opportunityId}/application")
public class ApplicationReviewController {

	private final ClientApplicationService applications;

	ApplicationReviewController(ClientApplicationService applications) {
		this.applications = applications;
	}

	/**
	 * The application behind a deal, or a <strong>null payload</strong> when the deal did not come
	 * from the portal.
	 *
	 * <p>A deal a salesperson opened by hand has no application and never will, which is an
	 * ordinary state rather than a failure — so it is 200 with nothing in it, and the panel shows
	 * nothing. A 404 for the common case would teach this screen to treat errors as normal, and
	 * then a real failure would look the same as a phoned-in deal.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', "
			+ "'PROJECT_COORDINATOR', 'CASE_MANAGER')")
	public ApiResponse<ClientApplicationService.ApplicationView> read(@PathVariable String opportunityId) {
		return ApiResponse.ok(applications.forOpportunity(opportunityId));
	}
}
