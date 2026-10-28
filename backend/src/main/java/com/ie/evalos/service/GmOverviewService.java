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
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GM's monthly overview: the money, where it came from, and the four departments behind it.
 *
 * <p><strong>Two halves, and the split is the design.</strong> The <em>production</em> half is
 * EvalOS's own rows — cases, services, deliveries, lateness. The <em>pipeline</em> half is GHL's
 * deals as the mirror holds them, so it is answerable whenever the database is; how current it is
 * is the sweep's question, shown on the board's sync stamp. {@code pipelineUnavailable} stays on the
 * payload for the screen and is always null since the mirror read (2026-10-13).
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
 * <p><strong>The pipeline half reads the mirror, never GHL live</strong> (2026-10-13, as D76's desk
 * dashboards already did). It used to make up to three paged GHL searches per desk and pipeline, one
 * after another under the shared rate limit: 18.8 s for one page load. The mirror carries every field
 * the figures need ({@code ghl_created_at}, status, {@code last_status_change_at}, amount, source,
 * custom fields), and a win is placed by its status-change time directly, so the created-date lookback
 * GHL's search forced no longer bounds the "won" count.
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
	private final SellingBrand sellingBrand;
	private final BigDecimal monthlyGoal;
	private final JdbcTemplate jdbc;
	private final TeamMemberPipelineRepository assignments;
	private final OpportunityMirrorService mirror;

	GmOverviewService(CaseLifecycleService lifecycle, TeamMemberRepository teamMembers,
			SellingBrand sellingBrand, JdbcTemplate jdbc, TeamMemberPipelineRepository assignments,
			OpportunityMirrorService mirror,
			@Value("${evalos.sales.monthly-goal}") BigDecimal monthlyGoal) {
		this.lifecycle = lifecycle;
		this.jdbc = jdbc;
		this.assignments = assignments;
		this.mirror = mirror;
		this.teamMembers = teamMembers;
		this.monthlyGoal = monthlyGoal;
		this.sellingBrand = sellingBrand;
	}

	// --- the payload ---------------------------------------------------------

	/**
	 * Won money against the month's target.
	 *
	 * <p>{@code goal} and {@code pctToGoal} are <strong>null on any window that is not a calendar
	 * month</strong>, and that is the whole reason they are boxed. The target is a monthly number;
	 * printing "12% to goal" over a week, or "430%" over a year, would be arithmetic dressed as a
	 * business figure. The tile drops to the bare amount instead of lying about the denominator.
	 *
	 * <p>{@code won} is <strong>the sales desks' wins only</strong>. Marketing nurtures the same
	 * lead Sales closes, so adding a marketing desk's won value counts that deal twice; the
	 * marketing desk's own figure stays on its row under "By desk".
	 *
	 * @param goalMonth the first of the month {@code goal} belongs to — what {@code PUT
	 *                  /metrics/gm/goal} takes — or null on a window that is not a calendar month
	 */
	public record Headline(BigDecimal won, BigDecimal goal, Integer pctToGoal, LocalDate goalMonth) {
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
	 * @param comparedFrom first day of that previous window, so the screen can say what "−60%" is against
	 * @param comparedTo   its last day (the day before the current window starts)
	 */
	public record Sales(int newLeads, BigDecimal newValue, int won, BigDecimal wonValue, Integer wonDeltaPct,
			LocalDate comparedFrom, LocalDate comparedTo) {
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
	/**
	 * @param hot      open deals in a Hot stage right now ({@link #tileOf}), like {@code open} independent of the window
	 * @param invoiced value of open deals at Invoice Sent right now
	 * @param meetings meetings this member booked that start inside the window
	 * @param monthLeads    deals opened this calendar month on the desk's pipelines
	 * @param monthWonValue value won this calendar month on the desk's pipelines
	 * @param monthMeetings meetings this member booked that start this calendar month
	 *                      <p>The last three are the GM's Team card (edited 2026-10-29): this month's stats, whatever
	 *                      the range filter says. {@code newLeads}/{@code won}/{@code wonValue} stay windowed — the
	 *                      monthly targets read them.
	 */
	public record DeskRow(UUID memberId, String name, Role role, int newLeads, int won, BigDecimal wonValue,
			int open, BigDecimal openValue, int hot, BigDecimal invoiced, int meetings, int monthLeads,
			BigDecimal monthWonValue, int monthMeetings) {
	}

	/** One pipeline's figures for the Team card. */
	private record PipelineMonth(int leads, BigDecimal wonValue) {
	}

	/**
	 * Per pipeline, for one brand: deals opened in [from, to) and the value won in [from, to). Parameters: from, to,
	 * from, to, brand. Public for the schema test.
	 */
	public static final String TEAM_PIPELINE_MONTH = "SELECT p.ghl_id,"
			+ " count(*) FILTER (WHERE o.ghl_created_at >= ? AND o.ghl_created_at < ?) AS leads,"
			+ " coalesce(sum(o.amount) FILTER (WHERE lower(trim(o.status)) = 'won'"
			+ " AND o.last_status_change_at >= ? AND o.last_status_change_at < ?), 0) AS won_value"
			+ " FROM opportunity o JOIN pipeline p ON p.id = o.pipeline_id"
			+ " WHERE o.brand_id = ? AND o.missing_since IS NULL GROUP BY p.ghl_id";

	/** Meetings per booking member for one brand, starting in [from, to). Public for the schema test. */
	public static final String TEAM_MEETINGS_MONTH = "SELECT booked_by, count(*) AS n FROM meeting"
			+ " WHERE brand_id = ? AND starts_at >= ? AND starts_at < ? GROUP BY booked_by";

	/**
	 * Expert candidates opened this month on the brand's {@code EXPERT_HIRING} pipelines, and how many of them stand at
	 * "Meeting Done" or a later stage other than "Dropped". Public for the schema test. ponytail: the stage held now,
	 * not a history — a candidate dropped after the meeting no longer counts as met; a stage-change log is the upgrade.
	 */
	public static final String HIRING_THIS_MONTH = "SELECT count(*) AS interested,"
			+ " count(*) FILTER (WHERE lower(trim(s.name)) <> 'dropped' AND s.position >= (SELECT min(m.position)"
			+ " FROM pipeline_stage m WHERE m.pipeline_id = p.id AND lower(trim(m.name)) = 'meeting done')) AS met"
			+ " FROM opportunity o JOIN pipeline p ON p.id = o.pipeline_id"
			+ " LEFT JOIN pipeline_stage s ON s.pipeline_id = p.id AND s.ghl_id = o.ghl_stage_id"
			+ " WHERE o.brand_id = ? AND p.purpose = 'EXPERT_HIRING' AND o.missing_since IS NULL"
			+ " AND o.ghl_created_at >= ? AND o.ghl_created_at < ?";

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

	/**
	 * One slice of the period, for the trend chart: the sales desks' new leads and won value, and the same
	 * slice of the previous period of equal length ({@code start} is where the current slice begins).
	 */
	public record TrendPoint(LocalDate start, int leads, BigDecimal won, int previousLeads, BigDecimal previousWon) {
	}

	/**
	 * The period cut into at most {@link #TREND_BUCKETS} equal slices — daily up to a month, wider beyond —
	 * so one rule serves every range the shell's filter can make. The mirror holds every opportunity, so
	 * the previous period is always complete.
	 */
	public record Trend(int bucketDays, List<TrendPoint> points) {
	}

	public record GmOverview(Headline headline, List<SourceRow> bySource, List<ServiceRow> byService, Sales sales,
			List<DeskRow> desks, Marketing marketing, Evaluation evaluation, Trend trend, Instant readAt,
			String pipelineUnavailable, Today today) {

		/** Without today's figures — what a hand-built fixture needs. */
		public GmOverview(Headline headline, List<SourceRow> bySource, List<ServiceRow> byService, Sales sales,
				List<DeskRow> desks, Marketing marketing, Evaluation evaluation, Trend trend, Instant readAt,
				String pipelineUnavailable) {
			this(headline, bySource, byService, sales, desks, marketing, evaluation, trend, readAt, pipelineUnavailable,
					null);
		}
	}

	/**
	 * Today and this calendar month so far, in the business's own zone, whatever range the dashboard is filtered to:
	 * sales leads opened, sales deals won, marketing leads opened, and the sales leads opened this month that are still
	 * open. Each with its value; counted once per deal, like the windowed figures. Also the expert candidates opened
	 * this month on the hiring pipelines and how many have had their meeting ({@link #HIRING_THIS_MONTH}).
	 */
	public record Today(int salesLeads, BigDecimal salesLeadValue, int won, BigDecimal wonValue, int marketingLeads,
			BigDecimal marketingLeadValue, int monthWon, BigDecimal monthWonValue, int monthSalesLeads,
			BigDecimal monthSalesLeadValue, int monthMarketingLeads, BigDecimal monthMarketingLeadValue,
			int monthOpenLeads, BigDecimal monthOpenLeadValue, Changes changes, StageNow newLeads, StageNow hot,
			StageNow invoiceSent, int hiringInterested, int hiringMet) {
	}

	/** Open deals standing in one kind of stage right now, across every desk's pipelines, each counted once. */
	public record StageNow(int deals, BigDecimal value) {
	}

	/**
	 * Which top-row tile an open deal's stage feeds, by the stage's name: Sales pipelines say "Lead", "Hot" and
	 * "Invoice Sent", Marketing ones "New Lead" and "Hot Leads". ponytail: matched on GHL's stage names, so a stage
	 * renamed in GHL drops out of its tile (display only — nothing routes on it); an EvalOS-owned stage kind like
	 * {@code pipeline_stage.won} is the upgrade if that bites.
	 */
	static String tileOf(String stageName) {
		return switch (stageName == null ? "" : stageName.strip().toLowerCase(Locale.ROOT)) {
			case "lead", "new lead", "new leads" -> "new";
			case "hot", "hot lead", "hot leads" -> "hot";
			case "invoice sent" -> "invoice";
			default -> null;
		};
	}

	/**
	 * The arrows on those tiles, as whole percentages: today against yesterday, and this month so far against the
	 * same number of days at the start of last month. Null where the earlier figure was zero — growth from nothing is
	 * not a percentage. Still-open leads have none: "still open" a month later is not the same question.
	 */
	public record Changes(Integer wonValue, Integer won, Integer salesLeads, Integer marketingLeads, Integer monthWon,
			Integer monthSalesLeads, Integer monthMarketingLeads) {
	}

	private static final int TREND_BUCKETS = 31;

	private static final StageNow NONE = new StageNow(0, BigDecimal.ZERO);

	// --- the read ------------------------------------------------------------

	@Transactional(readOnly = true)
	public GmOverview forCaller(DateWindow window, UUID brandId) {
		Evaluation evaluation = evaluation(window, brandId);
		List<ServiceRow> byService = byService(window, brandId);

		return pipeline(window, evaluation, byService);
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
		LocalDate previousFrom = from.minusDays(span);
		int bucketDays = (int) Math.ceil(span / (double) TREND_BUCKETS);
		int buckets = (int) Math.ceil(span / (double) bucketDays);
		int[] trendLeads = new int[buckets];
		int[] trendPreviousLeads = new int[buckets];
		BigDecimal[] trendWon = new BigDecimal[buckets];
		BigDecimal[] trendPreviousWon = new BigDecimal[buckets];
		java.util.Arrays.fill(trendWon, BigDecimal.ZERO);
		java.util.Arrays.fill(trendPreviousWon, BigDecimal.ZERO);

		// GHL's API cannot set an opportunity's native Source, so the back-fill (and any desk that
		// fills it) writes the "Lead Source" custom field. A blank Source falls back to it, the
		// same fallback the board's cards already make.
		String leadSourceFieldId = jdbc.query(
				"SELECT ghl_id FROM ghl_custom_field WHERE brand_id = ? AND model = 'opportunity' "
						+ "AND field_key = 'opportunity.lead_source'",
				(rs, row) -> rs.getString(1), sellingBrand.id()).stream().findFirst().orElse(null);

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
		BigDecimal previousWonValue = BigDecimal.ZERO;

		// A pipeline is read once however many desks hold it, and a deal counts once in the totals: two desks
		// granted the same pipeline each show it on their own row, but "Business won" must not double it.
		Map<String, List<Opportunity>> byPipeline = new java.util.HashMap<>();
		java.util.Set<String> counted = new java.util.HashSet<>();

		LocalDate today = LocalDate.now(BusinessCalendar.clock());
		Instant todayStart = today.atStartOfDay(window.zone()).toInstant();
		Instant todayEnd = today.plusDays(1).atStartOfDay(window.zone()).toInstant();
		Instant monthStart = today.withDayOfMonth(1).atStartOfDay(window.zone()).toInstant();
		Instant yesterdayStart = today.minusDays(1).atStartOfDay(window.zone()).toInstant();
		// Last month's first N days, N being today's day of the month (capped at that month's length).
		LocalDate lastMonth = today.withDayOfMonth(1).minusMonths(1);
		Instant lastMonthStart = lastMonth.atStartOfDay(window.zone()).toInstant();
		Instant lastMonthSoFar = lastMonth.plusDays(Math.min(today.getDayOfMonth(), lastMonth.lengthOfMonth()))
				.atStartOfDay(window.zone()).toInstant();
		int yesterdaySalesLeads = 0;
		int yesterdayMarketingLeads = 0;
		int yesterdayWon = 0;
		BigDecimal yesterdayWonValue = BigDecimal.ZERO;
		int lastMonthWon = 0;
		int lastMonthSalesLeads = 0;
		int lastMonthMarketingLeads = 0;
		int monthWon = 0;
		BigDecimal monthWonValue = BigDecimal.ZERO;
		int monthSalesLeads = 0;
		int monthMarketingLeads = 0;
		int monthOpenLeads = 0;
		BigDecimal monthSalesLeadValue = BigDecimal.ZERO;
		BigDecimal monthMarketingLeadValue = BigDecimal.ZERO;
		BigDecimal monthOpenLeadValue = BigDecimal.ZERO;
		int todaySalesLeads = 0;
		int todayMarketingLeads = 0;
		int todayWon = 0;
		BigDecimal todaySalesLeadValue = BigDecimal.ZERO;
		BigDecimal todayMarketingLeadValue = BigDecimal.ZERO;
		BigDecimal todayWonValue = BigDecimal.ZERO;

		// The earliest instant any figure below looks at. Every figure counts a deal by when it was opened or when
		// its status last changed, except "open now", which the query keeps whatever its dates — so a deal opened
		// and settled before this answers nothing here, and is not loaded.
		Map<String, String> stageNames = new java.util.HashMap<>();
		jdbc.query("SELECT ghl_id, name FROM pipeline_stage WHERE brand_id = ? AND missing_since IS NULL",
				(rs) -> {
					stageNames.put(rs.getString(1), rs.getString(2));
				}, sellingBrand.id());
		Map<String, StageNow> stagesNow = new java.util.HashMap<>();

		Instant since = java.util.stream.Stream.of(previousStart, windowStart, lastMonthStart, yesterdayStart, monthStart)
				.min(Comparator.naturalOrder()).orElseThrow();

		// Meetings per booking member in the window — one grouped read, not one per desk.
		Map<UUID, Integer> meetingsBy = new java.util.HashMap<>();
		jdbc.query("SELECT booked_by, count(*) AS n FROM meeting WHERE brand_id = ? AND starts_at >= ? AND starts_at < ?"
				+ " GROUP BY booked_by", (rs) -> {
					meetingsBy.put(rs.getObject("booked_by", UUID.class), rs.getInt("n"));
				}, sellingBrand.id(), java.sql.Timestamp.from(windowStart), java.sql.Timestamp.from(windowEnd));

		// The Team card's figures (2026-10-29): this calendar month so far, whatever the range filter says. One grouped
		// read each — per pipeline, and per booking member.
		java.sql.Timestamp teamFrom = java.sql.Timestamp.from(monthStart);
		java.sql.Timestamp teamTo = java.sql.Timestamp.from(todayEnd);
		Map<String, PipelineMonth> pipelineMonth = new java.util.HashMap<>();
		jdbc.query(TEAM_PIPELINE_MONTH, (rs) -> {
			pipelineMonth.put(rs.getString("ghl_id"), new PipelineMonth(rs.getInt("leads"), rs.getBigDecimal("won_value")));
		}, teamFrom, teamTo, teamFrom, teamTo, sellingBrand.id());
		Map<UUID, Integer> meetingsMonthBy = new java.util.HashMap<>();
		jdbc.query(TEAM_MEETINGS_MONTH, (rs) -> {
			meetingsMonthBy.put(rs.getObject("booked_by", UUID.class), rs.getInt("n"));
		}, sellingBrand.id(), teamFrom, teamTo);

		int[] hiring = new int[2];
		jdbc.query(HIRING_THIS_MONTH, (rs) -> {
			hiring[0] = rs.getInt("interested");
			hiring[1] = rs.getInt("met");
		}, sellingBrand.id(), java.sql.Timestamp.from(monthStart), java.sql.Timestamp.from(todayEnd));

		for (TeamMember desk : desks) {
			// The desk's pipelines are the set they are granted (D19b), the same one their board draws —
			// not `team_member.ghl_pipeline_id`, which nothing has written since Unit 44b. A desk on two
			// pipelines is both of them here, and a desk granted one after that migration is on this card.
			List<String> deskPipelines = assignments.ghlIdsFor(desk.getId());
			List<Opportunity> rows = deskPipelines.stream()
					.flatMap((id) -> byPipeline.computeIfAbsent(id, (key) -> mirror.onPipelinesSince(List.of(key), since))
							.stream())
					.toList();
			boolean sales = desk.getRole() != Role.MARKETING;
			String group = sales ? "S:" : "M:";

			int deskNew = 0;
			int deskWon = 0;
			BigDecimal deskNewValue = BigDecimal.ZERO;
			BigDecimal deskWonValue = BigDecimal.ZERO;

			for (Opportunity row : rows) {
				Instant createdAt = row.getGhlCreatedAt();
				boolean firstForGroup = counted.add(group + row.getGhlId());
				boolean firstAtAll = counted.add("A:" + row.getGhlId());
				Instant wonAt = WON.equalsIgnoreCase(row.getStatus()) ? row.getLastStatusChangeAt() : null;
				String tile = firstAtAll && OpportunityBoardService.isOpen(row) ? tileOf(stageNames.get(row.getGhlStageId())) : null;
				if (tile != null) {
					stagesNow.merge(tile, new StageNow(1, amountOf(row)),
							(a, b) -> new StageNow(a.deals() + b.deals(), a.value().add(b.value())));
				}

				if (firstForGroup && within(createdAt, todayStart, todayEnd)) {
					if (sales) {
						todaySalesLeads++;
						todaySalesLeadValue = todaySalesLeadValue.add(amountOf(row));
					}
					else {
						todayMarketingLeads++;
						todayMarketingLeadValue = todayMarketingLeadValue.add(amountOf(row));
					}
				}
				if (sales && firstForGroup && within(wonAt, todayStart, todayEnd)) {
					todayWon++;
					todayWonValue = todayWonValue.add(amountOf(row));
				}
				if (sales && firstForGroup && within(wonAt, monthStart, todayEnd)) {
					monthWon++;
					monthWonValue = monthWonValue.add(amountOf(row));
				}
				if (firstForGroup && within(createdAt, yesterdayStart, todayStart)) {
					if (sales) {
						yesterdaySalesLeads++;
					}
					else {
						yesterdayMarketingLeads++;
					}
				}
				if (firstForGroup && within(createdAt, lastMonthStart, lastMonthSoFar)) {
					if (sales) {
						lastMonthSalesLeads++;
					}
					else {
						lastMonthMarketingLeads++;
					}
				}
				if (sales && firstForGroup && within(wonAt, yesterdayStart, todayStart)) {
					yesterdayWon++;
					yesterdayWonValue = yesterdayWonValue.add(amountOf(row));
				}
				if (sales && firstForGroup && within(wonAt, lastMonthStart, lastMonthSoFar)) {
					lastMonthWon++;
				}
				if (firstForGroup && within(createdAt, monthStart, todayEnd)) {
					if (sales) {
						monthSalesLeads++;
						monthSalesLeadValue = monthSalesLeadValue.add(amountOf(row));
						if (OpportunityBoardService.isOpen(row)) {
							monthOpenLeads++;
							monthOpenLeadValue = monthOpenLeadValue.add(amountOf(row));
						}
					}
					else {
						monthMarketingLeads++;
						monthMarketingLeadValue = monthMarketingLeadValue.add(amountOf(row));
					}
				}

				if (within(createdAt, windowStart, windowEnd)) {
					deskNew++;
					deskNewValue = deskNewValue.add(amountOf(row));
					if (firstAtAll) {
						opportunities++;
						if (isBlank(sourceOf(row, leadSourceFieldId))) {
							unattributed++;
						}
					}
					if (firstForGroup) {
						if (sales) {
							salesNew++;
							salesNewValue = salesNewValue.add(amountOf(row));
							// The trend is the sales desks', like the headline it sits under.
							bump(trendLeads, createdAt, from, bucketDays, window);
						}
						else {
							marketingNew++;
							marketingNewValue = marketingNewValue.add(amountOf(row));
						}
					}
				}
				else if (sales && firstForGroup && within(createdAt, previousStart, windowStart)) {
					bump(trendPreviousLeads, createdAt, previousFrom, bucketDays, window);
				}

				// Placed by when it was won, not when it was opened: a deal opened in July and won in September is
				// September's win.
				if (within(wonAt, windowStart, windowEnd)) {
					deskWon++;
					deskWonValue = deskWonValue.add(amountOf(row));
					// Sales wins only, same rule as the headline these bars split.
					if (sales && firstForGroup) {
						salesWon++;
						salesWonValue = salesWonValue.add(amountOf(row));
						merge(sourceTotals, row, leadSourceFieldId);
						add(trendWon, wonAt, from, bucketDays, window, amountOf(row));
					}
				}
				// Sales desks only, like the current window's salesWon: counting Marketing's wins here as well made
				// the "before" figure larger than the "now" one it is compared with, so the change read too low.
				else if (sales && firstForGroup && within(wonAt, previousStart, windowStart)) {
					previousWon++;
					previousWonValue = previousWonValue.add(amountOf(row));
					add(trendPreviousWon, wonAt, previousFrom, bucketDays, window, amountOf(row));
				}
			}

			// What the desk holds open right now, from the mirror — the figure their board's header shows.
			// Independent of the date window, which only scopes New / Won / Value.
			List<Opportunity> openNow = rows.stream().filter(OpportunityBoardService::isOpen).toList();
			BigDecimal openValue = openNow.stream().map(Opportunity::getAmount)
					.filter((amount) -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
			int deskHot = (int) openNow.stream().filter((row) -> "hot".equals(tileOf(stageNames.get(row.getGhlStageId())))).count();
			BigDecimal deskInvoiced = openNow.stream()
					.filter((row) -> "invoice".equals(tileOf(stageNames.get(row.getGhlStageId()))))
					.map(Opportunity::getAmount).filter((amount) -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
			List<PipelineMonth> month = deskPipelines.stream().distinct().map(pipelineMonth::get)
					.filter(java.util.Objects::nonNull).toList();
			deskRows.add(new DeskRow(desk.getId(), desk.getDisplayName(), desk.getRole(), deskNew, deskWon,
					deskWonValue, openNow.size(), openValue, deskHot, deskInvoiced, meetingsBy.getOrDefault(desk.getId(), 0),
					month.stream().mapToInt(PipelineMonth::leads).sum(),
					month.stream().map(PipelineMonth::wonValue).reduce(BigDecimal.ZERO, BigDecimal::add),
					meetingsMonthBy.getOrDefault(desk.getId(), 0)));
		}

		bySource.addAll(sourceTotals.values().stream()
				.sorted(Comparator.comparing(SourceRow::value, Comparator.reverseOrder())
						.thenComparing(SourceRow::source))
				.toList());

		return new GmOverview(
				headline(window, salesWonValue),
				bySource,
				byService,
				new Sales(salesNew, salesNewValue, salesWon, salesWonValue,
						deltaPct(previousWonValue, salesWonValue, previousWon), previousFrom, from.minusDays(1)),
				deskRows.stream().sorted(Comparator.comparing(DeskRow::name)).toList(),
				new Marketing(marketingNew, marketingNewValue,
						opportunities == 0 ? null : Math.round(unattributed * 100f / opportunities)),
				evaluation,
				trend(from, bucketDays, trendLeads, trendPreviousLeads, trendWon, trendPreviousWon),
				Instant.now(),
				null,
				new Today(todaySalesLeads, todaySalesLeadValue, todayWon, todayWonValue, todayMarketingLeads,
						todayMarketingLeadValue, monthWon, monthWonValue, monthSalesLeads, monthSalesLeadValue,
						monthMarketingLeads, monthMarketingLeadValue, monthOpenLeads, monthOpenLeadValue,
						new Changes(deltaPct(yesterdayWonValue, todayWonValue, yesterdayWon), change(yesterdayWon, todayWon),
								change(yesterdaySalesLeads, todaySalesLeads),
								change(yesterdayMarketingLeads, todayMarketingLeads), change(lastMonthWon, monthWon),
								change(lastMonthSalesLeads, monthSalesLeads),
								change(lastMonthMarketingLeads, monthMarketingLeads)),
						stagesNow.getOrDefault("new", NONE), stagesNow.getOrDefault("hot", NONE),
						stagesNow.getOrDefault("invoice", NONE), hiring[0], hiring[1]));
	}

	private static Trend trend(LocalDate from, int bucketDays, int[] leads, int[] previousLeads,
			BigDecimal[] won, BigDecimal[] previousWon) {
		List<TrendPoint> points = new ArrayList<>();
		for (int i = 0; i < leads.length; i++) {
			points.add(new TrendPoint(from.plusDays((long) i * bucketDays), leads[i], won[i], previousLeads[i],
					previousWon[i]));
		}
		return new Trend(bucketDays, points);
	}

	/** The slice an instant falls in, counted from {@code base}; -1 when it is outside the period. */
	private static int slice(Instant at, LocalDate base, int bucketDays, int buckets, DateWindow window) {
		if (at == null) {
			return -1;
		}
		long day = at.atZone(window.zone()).toLocalDate().toEpochDay() - base.toEpochDay();
		int index = (int) (day / bucketDays);
		return day < 0 || index >= buckets ? -1 : index;
	}

	private static void bump(int[] counts, Instant at, LocalDate base, int bucketDays, DateWindow window) {
		int index = slice(at, base, bucketDays, counts.length, window);
		if (index >= 0) {
			counts[index]++;
		}
	}

	private static void add(BigDecimal[] sums, Instant at, LocalDate base, int bucketDays, DateWindow window,
			BigDecimal amount) {
		int index = slice(at, base, bucketDays, sums.length, window);
		if (index >= 0) {
			sums[index] = sums[index].add(amount);
		}
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
		if (sellingBrand.id() == null) {
			return List.of();
		}
		List<TeamMember> all = new ArrayList<>();
		all.addAll(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, sellingBrand.id()));
		all.addAll(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, sellingBrand.id()));
		return all.stream().filter((member) -> !assignments.ghlIdsFor(member.getId()).isEmpty()).toList();
	}

	private Headline headline(DateWindow window, BigDecimal won) {
		boolean monthly = window.range() == DateRange.MONTH || window.range() == DateRange.LAST_MONTH;
		if (!monthly) {
			return new Headline(won, null, null, null);
		}
		LocalDate month = window.from().withDayOfMonth(1);
		BigDecimal goal = goalFor(month);
		if (goal.signum() <= 0) {
			return new Headline(won, null, null, month);
		}
		return new Headline(won, goal,
				won.multiply(BigDecimal.valueOf(100)).divide(goal, 0, RoundingMode.HALF_UP).intValue(), month);
	}

	/** The newest target the GM set for this month, else {@code SALES_MONTHLY_GOAL}. */
	BigDecimal goalFor(LocalDate month) {
		if (sellingBrand.id() == null) {
			return monthlyGoal;
		}
		return jdbc.query("SELECT amount FROM sales_monthly_goal WHERE brand_id = ? AND month = ? "
				+ "ORDER BY set_at DESC LIMIT 1", (rs, n) -> rs.getBigDecimal(1), sellingBrand.id(), month)
				.stream().findFirst().orElse(monthlyGoal);
	}

	/**
	 * Sets the selling brand's target for one month. A new row every time — the older ones are
	 * who moved the goal and when, and are never updated (append-only truth).
	 */
	@Transactional
	public void setGoal(LocalDate month, BigDecimal amount, UUID setBy) {
		if (sellingBrand.id() == null) {
			throw new InvalidRequestException("No selling brand is configured (evalos.ghl.sales-brand)");
		}
		if (amount == null || amount.signum() < 0) {
			throw new InvalidRequestException("A monthly target is zero or more");
		}
		jdbc.update("INSERT INTO sales_monthly_goal (brand_id, month, amount, set_by) VALUES (?, ?, ?, ?)",
				sellingBrand.id(), month.withDayOfMonth(1), amount, setBy);
	}

	/** A count's change as a whole percentage; null when there was nothing before. */
	private static Integer change(int previous, int current) {
		return previous == 0 ? null : Math.round((current - previous) * 100f / previous);
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

	/**
	 * The Lead Source custom field, else the mirrored Source; null if neither.
	 *
	 * <p>The custom field first because it is the deliberate attribution (the back-fill and the desks write
	 * it; GHL's API cannot set the native Source), while the mirror's {@code source} is GHL's native one or,
	 * failing that, the contact's — a fallback that must not outrank it. ponytail: the mirror does not keep
	 * native and contact-derived apart, so a native Source set in GHL's UI now loses to a differing custom
	 * field; a {@code source_origin} column is the upgrade if that ever matters.
	 */
	private static String sourceOf(Opportunity opportunity, String leadSourceFieldId) {
		String custom = leadSourceFieldId == null ? null : opportunity.getCustomFields().get(leadSourceFieldId);
		return !isBlank(custom) ? custom : opportunity.getSource();
	}

	private static boolean within(Instant at, Instant from, Instant to) {
		return at != null && !at.isBefore(from) && at.isBefore(to);
	}

	private static void merge(Map<String, SourceRow> totals, Opportunity opportunity, String leadSourceFieldId) {
		String source = sourceOf(opportunity, leadSourceFieldId);
		String name = isBlank(source) ? UNATTRIBUTED : source.trim();
		totals.merge(name.toLowerCase(Locale.ROOT), new SourceRow(name, 1, amountOf(opportunity)),
				(a, b) -> new SourceRow(a.source(), a.deals() + b.deals(), a.value().add(b.value())));
	}

	private static BigDecimal amountOf(Opportunity opportunity) {
		return opportunity.getAmount() == null ? BigDecimal.ZERO : opportunity.getAmount();
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
