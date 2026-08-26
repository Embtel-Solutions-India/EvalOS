package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.DateRange;
import com.ie.evalos.service.MarketingPipelineService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketing read: GHL's marketing funnels, for the GM.
 *
 * <p><strong>Its own controller rather than a sixth route on {@code MetricsController}.</strong>
 * Every route there answers a question about EvalOS's own cases, from EvalOS's own tables,
 * scoped to the caller. This one leaves the building — it reads a GHL sub-account over the
 * network, carries no brand, and fails 502 rather than 500 when the upstream is down. Sharing a
 * class with the case metrics would put an outbound integration behind the same door as the
 * production figures and blur which of them a reader is looking at.
 *
 * <p><strong>GM only, and there is no {@code brandId} parameter.</strong> Both follow from the
 * same fact: {@code evalos.ghl.location-id} is a global setting with no link to a brand, so this
 * number cannot be <em>attributed</em> to one, let alone narrowed to one. Accepting a brand id
 * would narrow nothing while implying it had. A Brand Manager is absent because the figure is
 * unattributable — not because it is a cross-brand roll-up: each brand has its own GHL
 * sub-account, so it is one brand's funnel and the server cannot prove whose. See
 * {@code MarketingPipelineService} for the premise this corrected.
 *
 * <p><strong>One route per funnel, and no {@code pipeline} parameter.</strong> The two routes
 * differ by one enum constant, which is nearly an argument for collapsing them into
 * {@code /pipeline/{name}} — but the name a caller could then pass is a live GHL pipeline name,
 * and the location holds five more that belong to other teams. A route per configured funnel
 * keeps the reachable set a deployment decision. It also keeps the existing URL, which is not
 * nothing: the ads route is already the one thing a saved dashboard link points at.
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

	private final MarketingPipelineService pipeline;

	MarketingController(MarketingPipelineService pipeline) {
		this.pipeline = pipeline;
	}

	/**
	 * The paid-search funnel.
	 *
	 * @param range one of {@code today}, {@code week}, {@code month}, {@code year} — the shell's own
	 *              date-filter vocabulary, shared with {@code MetricsController} through
	 *              {@link DateRange} so the control and this parameter cannot drift apart.
	 *              <p>
	 *              It filters on the opportunity's <strong>created-at</strong> date in GHL, so the
	 *              funnel answers "deals created in this window, grouped by the stage they are in
	 *              now". Defaulted to {@code year} to match the shell's own initial selection —
	 *              a different default here would put the control and the figures in disagreement
	 *              on first load. Moved from {@code month} with that control; the two are one
	 *              decision and have to be changed together.
	 */
	@GetMapping("/ads-pipeline")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<MarketingPipelineService.MarketingPipeline> adsPipeline(
			@RequestParam(defaultValue = "year") String range) {
		return ApiResponse.ok(pipeline.forCaller(MarketingPipelineService.Funnel.ADS, DateRange.parse(range)));
	}

	/**
	 * The email marketing funnel — the same read against a second GHL pipeline in the same
	 * location, named by {@code evalos.ghl.email-pipeline-name}.
	 *
	 * <p>Everything the route above says applies here unchanged: GM-only, no {@code brandId},
	 * 502 when GHL is down. It is the same location, so it is the same unattributable brand.
	 *
	 * @param range as above — the opportunity's created-at window, defaulted to {@code month} to
	 *              match the shell's initial selection
	 */
	@GetMapping("/email-pipeline")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<MarketingPipelineService.MarketingPipeline> emailPipeline(
			@RequestParam(defaultValue = "year") String range) {
		return ApiResponse.ok(pipeline.forCaller(MarketingPipelineService.Funnel.EMAIL, DateRange.parse(range)));
	}
}
