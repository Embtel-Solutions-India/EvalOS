package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.DateWindow;
import com.ie.evalos.service.BusinessCalendar;
import com.ie.evalos.service.CaseManagerMetricsService;
import com.ie.evalos.service.CoordinatorMetricsService;
import com.ie.evalos.service.DraftReviewService;
import com.ie.evalos.service.ExpertNetworkMetricsService;
import com.ie.evalos.service.NavBadgeService;
import com.ie.evalos.service.PmMetricsService;
import com.ie.evalos.service.RevenueMetricsService;
import com.ie.evalos.service.PmMetricsService.PmMetrics;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every role's dashboard figures, one route each.
 *
 * <p><strong>One controller, five routes, a separate service behind each.</strong> They share
 * nothing but this class: each role asks a different question of the same scoped case read, and
 * folding them into one payload would send every reader everybody's numbers and then trust the
 * client to hide what it should never have been sent.
 *
 * <p>Each gate is the narrowest one that lets its screen work, stated per route rather than as a
 * class-level annotation — a shared gate here would have to be the widest of the five.
 *
 * <p>{@code brandId} narrows and can only ever narrow — the service builds on the caller's
 * already-scoped case read, so passing another brand's id yields nothing rather than that
 * brand's numbers. Same contract as {@code CaseBoardController}, and the same reason it is safe
 * to accept a brand from a query string here at all.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

	private final PmMetricsService metrics;
	private final CoordinatorMetricsService coordinator;
	private final CaseManagerMetricsService caseManager;
	private final ExpertNetworkMetricsService network;
	private final RevenueMetricsService revenue;
	private final NavBadgeService navBadges;
	private final DraftReviewService drafts;

	MetricsController(PmMetricsService metrics, CoordinatorMetricsService coordinator,
			CaseManagerMetricsService caseManager, ExpertNetworkMetricsService network,
			RevenueMetricsService revenue, NavBadgeService navBadges, DraftReviewService drafts) {
		this.metrics = metrics;
		this.coordinator = coordinator;
		this.caseManager = caseManager;
		this.network = network;
		this.revenue = revenue;
		this.navBadges = navBadges;
		this.drafts = drafts;
	}

	/**
	 * @param range one of {@code today}, {@code week}, {@code month}, {@code year},
	 *              {@code last-month}, {@code last-year} or {@code custom} — the shell's own date
	 *              filter vocabulary, so the header control and this parameter cannot drift apart.
	 *              <p>
	 *              <strong>The four "this" ranges are calendar-to-date and that changed what this
	 *              endpoint returns.</strong> {@code month} used to mean the last 30 days and now
	 *              means since the 1st, so on the 3rd of a month this reports a far smaller figure
	 *              than the same call did before — correctly, but anyone comparing against an old
	 *              screenshot will think something broke. The labels are what made the old
	 *              behaviour wrong: a control saying "This month" that answered for 30 days
	 *              spanning two of them was stating something untrue.
	 * @param from  ISO date, <strong>only</strong> with {@code range=custom}
	 * @param to    ISO date, only with {@code range=custom}. Supplying either on a named range is a
	 *              400 rather than silently ignored — see {@link DateWindow#of}
	 */
	@GetMapping("/pm")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<PmMetrics> pm(@RequestParam(defaultValue = "month") String range,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) UUID brandId) {
		// Whole days in the business's zone, not an offset from this instant. `BusinessCalendar`
		// already owns "what day is it here" for every SLA in the app, so a period boundary
		// resolved anywhere else would put this screen on a different day from the rest of EvalOS.
		DateWindow window = DateWindow.of(range, from, to, BusinessCalendar.clock());
		return ApiResponse.ok(metrics.forCaller(window.startInstant(), window.endInstant(), brandId));
	}

	/**
	 * The Coordinator's figures: what the client is holding up, and what has gone out.
	 *
	 * <p>No {@code range}. Every figure here is either live (documents outstanding, drafts with a
	 * client) or fixed-window by definition (delivered today, delivered this week), so a period
	 * selector would apply to nothing.
	 */
	@GetMapping("/coordinator")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR')")
	public ApiResponse<CoordinatorMetricsService.CoordinatorMetrics> coordinator(
			@RequestParam(required = false) UUID brandId) {
		return ApiResponse.ok(coordinator.forCaller(brandId));
	}

	/**
	 * One Case Manager's own docket.
	 *
	 * <p><strong>No {@code brandId} parameter, deliberately.</strong> This endpoint answers "my
	 * work", and the caller is the scope — the service keys on {@code TenantContext.memberId()}.
	 * A brand filter would be a way to ask the question about somebody else.
	 */
	@GetMapping("/case-manager")
	@PreAuthorize("hasAnyRole('GM', 'CASE_MANAGER')")
	public ApiResponse<CaseManagerMetricsService.CaseManagerMetrics> caseManager() {
		return ApiResponse.ok(caseManager.forCaller());
	}

	/**
	 * The supply side: roster health, coverage gaps, acceptance, onboarding.
	 *
	 * <p>Carries no case content and no client identity — the axis {@code architecture.md} draws.
	 * The Project Manager is on the gate because they pick experts and need to know where the
	 * bench is thin; the Coordinator and Case Manager are not, because they work the case an
	 * expert was already chosen for.
	 */
	@GetMapping("/expert-network")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<ExpertNetworkMetricsService.ExpertNetworkMetrics> expertNetwork() {
		return ApiResponse.ok(network.forCaller());
	}

	/**
	 * Money in versus delivered, for the two oversight roles and the PM.
	 *
	 * <p>The gate is {@code CaseController.SEES_DEAL_VALUE}'s membership, stated as roles because
	 * {@code @PreAuthorize} takes a string. **If that set ever changes, this changes with it** —
	 * a role that cannot see a deal value on a case must not read the brand's revenue here.
	 */
	@GetMapping("/revenue")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<RevenueMetricsService.RevenueMetrics> revenue(
			@RequestParam(required = false) UUID brandId) {
		return ApiResponse.ok(revenue.forCaller(brandId));
	}

	/**
	 * The draft review workspace: every draft in flight, with its state and progress.
	 *
	 * <p>Gated to the roles that actually act on a draft. The **Case Manager is deliberately
	 * absent**: this screen lists the whole team's drafts including other people's revision
	 * history, and a CM's own drafts are on their dashboard, scoped to them.
	 */
	@GetMapping("/drafts")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<DraftReviewService.DraftReview> drafts(@RequestParam(required = false) UUID brandId) {
		return ApiResponse.ok(drafts.forCaller(brandId));
	}

	/**
	 * The counts beside the navigation rail's screen names.
	 *
	 * <p>**Open to every authenticated staff role**, and that is safe rather than lax: each count
	 * is taken over the caller's already-scoped case read, so a role sees only what it could open
	 * anyway, and the rail renders a badge only beside a screen that role can actually reach.
	 * Gating it to a role list would mean a second copy of the nav table's role rules.
	 */
	@GetMapping("/nav")
	public ApiResponse<NavBadgeService.NavBadges> nav() {
		return ApiResponse.ok(navBadges.forCaller());
	}

}
