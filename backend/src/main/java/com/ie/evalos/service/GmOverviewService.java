package com.ie.evalos.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.common.DateRange;
import com.ie.evalos.common.DateWindow;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.TeamMemberRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GM's monthly overview: the money, where it came from, and the four departments behind it.
 *
 * <p><strong>Two halves, and the split is the design.</strong> The <em>production</em> half is
 * EvalOS's own rows — cases, services, deliveries, lateness — and is always answerable. The
 * <em>pipeline</em> half is GHL's, and GHL can be unconfigured, rate-limited or down. They are
 * returned in one payload with the pipeline half <strong>nullable</strong> rather than as two
 * endpoints, because the alternative is a screen where half the tiles 502 and the reader cannot
 * tell whether the business had no wins or whether nobody could ask. {@code pipelineUnavailable}
 * carries the reason, and the screen prints it on exactly the tiles it invalidates.
 *
 * <p><strong>Everything here is a figure nothing else already serves.</strong> The GM's screen
 * also reads {@code /metrics/revenue} (collected, recognised, open liability),
 * {@code /metrics/pm} (at risk, unassigned, on-time rate), {@code /metrics/expert-network}
 * (roster, onboarding) and {@code /opportunities/board} (open deals, by stage, untouched) — all
 * of which the GM could already call. A second copy of any of them here would be a second number
 * to keep in step with the first.
 *
 * <p><strong>The scope is the selling brand's own pipelines.</strong> Same set as the GM's
 * opportunity board — every pipeline owned by an active member of {@code evalos.ghl.sales-brand}
 * — so the overview and the board cannot disagree about what "the pipeline" means. That also
 * keeps the single-brand ceiling ({@code 00b}) intact without a second rule: a location EvalOS
 * cannot attribute to a brand is read only through members it can.
 *
 * <p><strong>ponytail: no cache.</strong> One page load is two GHL searches per desk — roughly
 * eight paced requests for a team of four, about a second, for a screen one person opens.
 *
 * <p>The upgrade path is {@code ghl_funnel_cache}, keyed {@code (funnel, window_key)}: it would
 * take a {@code "GM"} row with no migration, and {@code V25}'s comment on it is worth reading
 * first — it records why a per-process map was wrong (a completed background read lost on
 * restart, a handover state private to one instance, a rate-limit budget multiplied by the
 * instance count). Do that when this screen starts polling or a desk's month stops fitting in a
 * page or two — not before, because the version-race handling that comes with it is real code
 * protecting one reader.
 *
 * <p><strong>ponytail: that table is otherwise orphaned, and could not be dropped here.</strong>
 * Its only reader, {@code MarketingPipelineService}, went with the two funnel screens on
 * 2026-09-16, and the drop has nowhere to live: {@code V905} (a local seed) clears the table, so a
 * {@code DROP} must be ordered after it, and {@code MigrationTreeTest} fails the build on any
 * {@code db/migration} script numbered 900 or above — while editing {@code V905} is a checksum
 * mismatch that refuses the boot. Both guards are correct and neither is worth bending for an
 * empty table. **Drop it in the next change that rebaselines the seed tree**, or adopt it here as
 * this service's cache, which costs nothing and settles it either way.
 */
@Service
public class GmOverviewService {

	private static final Logger log = LoggerFactory.getLogger(GmOverviewService.class);

	/** GHL's own word for a closed-won opportunity. */
	private static final String WON = "won";

	/**
	 * What a blank {@code source} is called.
	 *
	 * <p>A word rather than an empty bar, because "we do not know" is a finding: the same read
	 * reports it as a percentage on its own tile, and an unlabelled slice would hide the thing the
	 * tile exists to escalate.
	 */
	private static final String UNATTRIBUTED = "Unattributed";

	private final CaseLifecycleService lifecycle;
	private final TeamMemberRepository teamMembers;
	private final GhlPipelineClient ghl;
	private final UUID sellingBrandId;
	private final BigDecimal monthlyGoal;
	private final int wonLookbackDays;

	GmOverviewService(CaseLifecycleService lifecycle, TeamMemberRepository teamMembers, GhlPipelineClient ghl,
			SellingBrand sellingBrand,
			@Value("${evalos.sales.monthly-goal}") BigDecimal monthlyGoal,
			@Value("${evalos.sales.won-lookback-days}") int wonLookbackDays) {
		this.lifecycle = lifecycle;
		this.teamMembers = teamMembers;
		this.ghl = ghl;
		this.monthlyGoal = monthlyGoal;
		this.wonLookbackDays = wonLookbackDays;
		this.sellingBrandId = sellingBrand.id();
	}

	// --- the payload ---------------------------------------------------------

	/**
	 * Won money against the month's target.
	 *
	 * <p>{@code goal} and {@code pctToGoal} are <strong>null on any window that is not a calendar
	 * month</strong>, and that is the whole reason they are boxed. The target is a monthly number;
	 * printing "12% to goal" over a week, or "430%" over a year, would be arithmetic dressed as a
	 * business figure. The tile drops to the bare amount instead of lying about the denominator.
	 */
	public record Headline(BigDecimal won, BigDecimal goal, Integer pctToGoal) {
	}

	/** One row of a group-by, as deals and money. Used for both source and service. */
	public record SourceRow(String source, int deals, BigDecimal value) {
	}

	/**
	 * One service line, on both axes a GM asks about: what is in the shop, and what left it.
	 *
	 * <p>Both are EvalOS's own — {@code service_type} is a case column and GHL has nowhere to put
	 * it — which is why "business by service" and "business by source" do not add up to the same
	 * total and are never shown as if they should.
	 */
	public record ServiceRow(ServiceType serviceType, int openCases, BigDecimal openValue, int delivered,
			BigDecimal deliveredValue) {
	}

	/**
	 * @param wonDeltaPct against the previous window of the same length, or null when the previous
	 *                    window had no wins — a rise from zero is not a percentage
	 */
	public record Sales(int newLeads, BigDecimal newValue, int won, BigDecimal wonValue, Integer wonDeltaPct) {
	}

	/**
	 * One salesperson's desk.
	 *
	 * <p><strong>Keyed on the pipeline, not on GHL's {@code assignedTo}.</strong> EvalOS has no
	 * mapping from a GHL user to a {@code team_member} — stated in
	 * {@code SalesOpportunityController} and still true — but it does own
	 * {@code team_member.ghl_pipeline_id}, which is the link Unit 36 built the whole
	 * pipeline-scoped role on. A desk is therefore exactly what that member can already see.
	 */
	public record DeskRow(UUID memberId, String name, Role role, int newLeads, int won, BigDecimal wonValue) {
	}

	/**
	 * @param noSourcePct share of this window's opportunities GHL holds no {@code source} for.
	 *                    Null rather than zero when there were no opportunities at all
	 */
	public record Marketing(int newLeads, BigDecimal newValue, Integer noSourcePct) {
	}

	/**
	 * @param late cases past their promised date and not delivered. <strong>Not</strong>
	 *             {@code PmMetrics.atRiskNow}, which also counts cases merely inside the red
	 *             band — a GM asking "how many are late" is asking about the ones that already
	 *             missed, and two tiles answering with different definitions of the same word is
	 *             how a dashboard stops being believed
	 */
	public record Evaluation(int delivered, BigDecimal deliveredValue, int late, int openCases,
			BigDecimal openValue) {
	}

	public record GmOverview(Headline headline, List<SourceRow> bySource, List<ServiceRow> byService, Sales sales,
			List<DeskRow> desks, Marketing marketing, Evaluation evaluation, Instant readAt,
			String pipelineUnavailable) {
	}

	// --- the read ------------------------------------------------------------

	@Transactional(readOnly = true)
	public GmOverview forCaller(DateWindow window, UUID brandId) {
		Evaluation evaluation = evaluation(window, brandId);
		List<ServiceRow> byService = byService(window, brandId);

		try {
			return pipeline(window, evaluation, byService);
		}
		catch (GhlUnavailableException unreachable) {
			// Degrade rather than 502. Production is EvalOS's own data and is still true when
			// GHL is not answering; refusing the whole payload would take four working tiles
			// down with the four that broke.
			log.warn("GM overview served without its pipeline half: {}", unreachable.getMessage());
			return new GmOverview(null, List.of(), byService, null, List.of(), null, evaluation, Instant.now(),
					unreachable.getMessage());
		}
	}

	private GmOverview pipeline(DateWindow window, Evaluation evaluation, List<ServiceRow> byService) {
		List<TeamMember> desks = desks();
		LocalDate from = window.from();
		LocalDate to = window.to();
		// The previous window of the same length, so "up 12%" compares like with like on any
		// range the shell's filter can produce, custom ones included.
		long span = to.toEpochDay() - from.toEpochDay() + 1;
		Instant windowStart = window.startInstant();
		Instant windowEnd = window.endInstant();
		Instant previousStart = from.minusDays(span).atStartOfDay(window.zone()).toInstant();

		List<SourceRow> bySource = new ArrayList<>();
		Map<String, SourceRow> sourceTotals = new LinkedHashMap<>();
		List<DeskRow> deskRows = new ArrayList<>();

		int salesNew = 0;
		int marketingNew = 0;
		int salesWon = 0;
		int previousWon = 0;
		int opportunities = 0;
		int unattributed = 0;
		BigDecimal salesNewValue = BigDecimal.ZERO;
		BigDecimal marketingNewValue = BigDecimal.ZERO;
		BigDecimal salesWonValue = BigDecimal.ZERO;
		BigDecimal headlineWon = BigDecimal.ZERO;
		BigDecimal previousWonValue = BigDecimal.ZERO;

		for (TeamMember desk : desks) {
			String pipelineId = desk.getGhlPipelineId();
			List<GhlPipelineClient.Opportunity> created = ghl.opportunitiesIn(pipelineId, from, to);
			// Wins are read over a wider created-window and bucketed here: GHL's date filter is on
			// createdAt, so a deal opened before this month and won inside it is invisible to the
			// query above. See GhlPipelineClient.opportunitiesIn(.., status).
			List<GhlPipelineClient.Opportunity> wins = ghl.opportunitiesIn(pipelineId,
					from.minusDays(wonLookbackDays), to, WON);

			int deskNew = created.size();
			int deskWon = 0;
			BigDecimal deskWonValue = BigDecimal.ZERO;
			BigDecimal deskNewValue = BigDecimal.ZERO;

			for (GhlPipelineClient.Opportunity opportunity : created) {
				deskNewValue = deskNewValue.add(amountOf(opportunity));
				opportunities++;
				if (isBlank(opportunity.source())) {
					unattributed++;
				}
			}

			for (GhlPipelineClient.Opportunity win : wins) {
				Instant at = win.lastStatusChangeAt();
				if (at == null) {
					continue;
				}
				if (!at.isBefore(windowStart) && at.isBefore(windowEnd)) {
					deskWon++;
					deskWonValue = deskWonValue.add(amountOf(win));
					merge(sourceTotals, win);
				}
				else if (!at.isBefore(previousStart) && at.isBefore(windowStart)) {
					previousWon++;
					previousWonValue = previousWonValue.add(amountOf(win));
				}
			}

			headlineWon = headlineWon.add(deskWonValue);
			if (desk.getRole() == Role.MARKETING) {
				marketingNew += deskNew;
				marketingNewValue = marketingNewValue.add(deskNewValue);
			}
			else {
				salesNew += deskNew;
				salesNewValue = salesNewValue.add(deskNewValue);
				salesWon += deskWon;
				salesWonValue = salesWonValue.add(deskWonValue);
			}
			deskRows.add(new DeskRow(desk.getId(), desk.getDisplayName(), desk.getRole(), deskNew, deskWon,
					deskWonValue));
		}

		bySource.addAll(sourceTotals.values().stream()
				.sorted(Comparator.comparing(SourceRow::value, Comparator.reverseOrder())
						.thenComparing(SourceRow::source))
				.toList());

		return new GmOverview(
				headline(window, headlineWon),
				bySource,
				byService,
				new Sales(salesNew, salesNewValue, salesWon, salesWonValue,
						deltaPct(previousWonValue, salesWonValue, previousWon)),
				deskRows.stream().sorted(Comparator.comparing(DeskRow::name)).toList(),
				new Marketing(marketingNew, marketingNewValue,
						opportunities == 0 ? null : Math.round(unattributed * 100f / opportunities)),
				evaluation,
				Instant.now(),
				null);
	}

	/**
	 * Every pipeline-scoped desk of the selling brand, with the member behind it.
	 *
	 * <p>Deliberately not {@code findPipelinesOfActiveMembers}, which returns ids alone: this
	 * screen names the salesperson, so it needs the row. The {@code ghl_pipeline_id IS NOT NULL}
	 * filter is the same one — a member with no pipeline has no desk to report on, and the
	 * {@code team_member_pipeline_matches_role} CHECK is what makes the two roles below the only
	 * ones that can have one.
	 */
	private List<TeamMember> desks() {
		if (sellingBrandId == null) {
			return List.of();
		}
		List<TeamMember> all = new ArrayList<>();
		all.addAll(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, sellingBrandId));
		all.addAll(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, sellingBrandId));
		return all.stream().filter((member) -> !isBlank(member.getGhlPipelineId())).toList();
	}

	private Headline headline(DateWindow window, BigDecimal won) {
		boolean monthly = window.range() == DateRange.MONTH || window.range() == DateRange.LAST_MONTH;
		if (!monthly || monthlyGoal.signum() <= 0) {
			return new Headline(won, null, null);
		}
		return new Headline(won, monthlyGoal,
				won.multiply(BigDecimal.valueOf(100)).divide(monthlyGoal, 0, RoundingMode.HALF_UP).intValue());
	}

	private static Integer deltaPct(BigDecimal previous, BigDecimal current, int previousDeals) {
		// Null rather than 100% when the previous window was empty: a first win is not an
		// improvement of any percentage, and a tile reading "up 100%" on a month that started
		// from nothing is the kind of figure that gets quoted at a board meeting.
		if (previousDeals == 0 || previous.signum() == 0) {
			return null;
		}
		return current.subtract(previous).multiply(BigDecimal.valueOf(100))
				.divide(previous, 0, RoundingMode.HALF_UP).intValue();
	}

	private static void merge(Map<String, SourceRow> totals, GhlPipelineClient.Opportunity opportunity) {
		String name = isBlank(opportunity.source()) ? UNATTRIBUTED : opportunity.source().trim();
		totals.merge(name.toLowerCase(Locale.ROOT), new SourceRow(name, 1, amountOf(opportunity)),
				(a, b) -> new SourceRow(a.source(), a.deals() + b.deals(), a.value().add(b.value())));
	}

	private static BigDecimal amountOf(GhlPipelineClient.Opportunity opportunity) {
		return opportunity.monetaryValue() == null ? BigDecimal.ZERO : opportunity.monetaryValue();
	}

	// --- the production half -------------------------------------------------

	private List<Case> scoped(UUID brandId) {
		return lifecycle.list(null, null, null).stream()
				.filter((subject) -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();
	}

	private List<ServiceRow> byService(DateWindow window, UUID brandId) {
		Instant from = window.startInstant();
		Instant to = window.endInstant();
		Map<ServiceType, int[]> counts = new LinkedHashMap<>();
		Map<ServiceType, BigDecimal[]> money = new LinkedHashMap<>();

		for (Case subject : scoped(brandId)) {
			ServiceType service = subject.getServiceType();
			if (service == null) {
				continue;
			}
			int[] count = counts.computeIfAbsent(service, (key) -> new int[2]);
			BigDecimal[] sums = money.computeIfAbsent(service,
					(key) -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
			BigDecimal value = subject.getDealValue() == null ? BigDecimal.ZERO : subject.getDealValue();
			Instant delivered = subject.getDeliveryDate();

			if (isOpen(subject)) {
				count[0]++;
				sums[0] = sums[0].add(value);
			}
			if (delivered != null && !delivered.isBefore(from) && delivered.isBefore(to)) {
				count[1]++;
				sums[1] = sums[1].add(value);
			}
		}

		return counts.entrySet().stream()
				.map((entry) -> new ServiceRow(entry.getKey(), entry.getValue()[0],
						money.get(entry.getKey())[0], entry.getValue()[1], money.get(entry.getKey())[1]))
				.sorted(Comparator.comparing((ServiceRow row) -> row.openValue().add(row.deliveredValue()))
						.reversed())
				.toList();
	}

	private Evaluation evaluation(DateWindow window, UUID brandId) {
		Instant from = window.startInstant();
		Instant to = window.endInstant();
		Instant now = Instant.now();
		int delivered = 0;
		int late = 0;
		int open = 0;
		BigDecimal deliveredValue = BigDecimal.ZERO;
		BigDecimal openValue = BigDecimal.ZERO;

		for (Case subject : scoped(brandId)) {
			BigDecimal value = subject.getDealValue() == null ? BigDecimal.ZERO : subject.getDealValue();
			Instant date = subject.getDeliveryDate();
			if (date != null && !date.isBefore(from) && date.isBefore(to)) {
				delivered++;
				deliveredValue = deliveredValue.add(value);
			}
			if (isOpen(subject)) {
				open++;
				openValue = openValue.add(value);
				if (subject.getDeadline() != null && subject.getDeadline().isBefore(now)) {
					late++;
				}
			}
		}
		return new Evaluation(delivered, deliveredValue, late, open, openValue);
	}

	/** In the shop: not yet delivered and not closed. The definition every tile here shares. */
	private static boolean isOpen(Case subject) {
		return subject.getDeliveryDate() == null && subject.getCurrentStage() != Stage.DELIVERED
				&& subject.getCurrentStage() != Stage.CLOSED;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
