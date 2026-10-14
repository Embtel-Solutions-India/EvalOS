package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.common.DateWindow;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The GM overview, and specifically the three ways it could quietly report a wrong number.
 *
 * <p>A win placed by the wrong timestamp, and a monthly goal applied to a window that is not a month.
 * The pipeline half reads the mirror (2026-10-13), so these fixtures are mirror rows, not GHL replies.
 */
class GmOverviewServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final String PIPELINE = "pipe-1";

	/** Mid-September, so "this month" is 1–15 September and the previous window is 17–31 August. */
	private static final Clock CLOCK = Clock.fixed(
			LocalDate.parse("2026-09-15").atTime(12, 0).atZone(BusinessCalendar.ZONE).toInstant(),
			BusinessCalendar.ZONE);

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final com.ie.evalos.repository.TeamMemberPipelineRepository assignments =
			mock(com.ie.evalos.repository.TeamMemberPipelineRepository.class);

	/** Each test desk's granted pipelines, by id — what `team_member_pipeline` answers (D19b). */
	private static final java.util.Map<UUID, List<String>> HELD = new java.util.concurrent.ConcurrentHashMap<>();

	private GmOverviewService service(String goal) {
		return service(goal, mock(JdbcTemplate.class));
	}

	private GmOverviewService service(String goal, JdbcTemplate jdbc) {
		org.mockito.Mockito.doAnswer((call) -> HELD.getOrDefault(call.<UUID>getArgument(0), List.of()))
				.when(assignments).ghlIdsFor(any());
		// A mocked JdbcTemplate answers every query with an empty list: no goal set on the
		// dashboard, so SALES_MONTHLY_GOAL (`goal`) is the fallback these tests exercise.
		return new GmOverviewService(lifecycle, teamMembers, new SellingBrand(BRAND), jdbc,
				assignments, mirror, new BigDecimal(goal), 180);
	}

	private static DateWindow window(String range) {
		return DateWindow.of(range, null, null, CLOCK);
	}

	private void givenOneSalesDesk() {
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND))
				.willReturn(List.of(desk(Role.SALES, PIPELINE)));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND)).willReturn(List.of());
	}

	private static TeamMember desk(Role role, String pipeline) {
		TeamMember desk = new TeamMember() {
		};
		ReflectionTestUtils.setField(desk, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(desk, "role", role);
		ReflectionTestUtils.setField(desk, "brandId", BRAND);
		ReflectionTestUtils.setField(desk, "displayName", role + " desk");
		ReflectionTestUtils.setField(desk, "ghlPipelineId", pipeline);
		HELD.put(desk.getId(), List.of(pipeline));
		ReflectionTestUtils.setField(desk, "active", true);
		return desk;
	}

	/**
	 * Marketing nurtures the lead Sales closes, so a marketing desk's win is the same deal again.
	 * Adding it to "Business won" counted the revenue twice.
	 */
	@Test
	void countsOnlySalesWinsInTheHeadline() {
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND))
				.willReturn(List.of(desk(Role.SALES, PIPELINE)));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND))
				.willReturn(List.of(desk(Role.MARKETING, "pipe-marketing")));
		given(mirror.onPipelines(List.of(PIPELINE)))
				.willReturn(List.of(won("5000", at("2026-09-04"), at("2026-09-01"), "Referral")));
		given(mirror.onPipelines(List.of("pipe-marketing")))
				.willReturn(List.of(won("5000", at("2026-09-04"), at("2026-08-01"), "Newsletter")));

		var overview = service("0").forCaller(window("month"), BRAND);

		assertThat(overview.headline().won()).isEqualByComparingTo("5000");
		assertThat(overview.bySource()).singleElement()
				.satisfies((row) -> assertThat(row.source()).isEqualTo("Referral"));
		// The marketing desk still shows its own figure on its row.
		assertThat(overview.desks()).anySatisfy((row) -> {
			assertThat(row.role()).isEqualTo(Role.MARKETING);
			assertThat(row.wonValue()).isEqualByComparingTo("5000");
		});
	}

	/**
	 * The change compares like with like: Sales wins now against Sales wins in the window before. A Marketing win in
	 * the earlier window used to be counted too, so a flat month read as a fall (here: −50% instead of 0%).
	 */
	@Test
	void theWonChangeComparesSalesWinsWithSalesWinsAndNamesItsWindow() {
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND))
				.willReturn(List.of(desk(Role.SALES, PIPELINE)));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND))
				.willReturn(List.of(desk(Role.MARKETING, "pipe-marketing")));
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(
				won("5000", at("2026-09-04"), at("2026-09-01"), "Referral"),
				won("5000", at("2026-08-20"), at("2026-08-01"), "Referral")));
		given(mirror.onPipelines(List.of("pipe-marketing")))
				.willReturn(List.of(won("5000", at("2026-08-20"), at("2026-08-01"), "Newsletter")));

		var sales = service("0").forCaller(window("month"), BRAND).sales();

		assertThat(sales.wonDeltaPct()).isZero();
		// "This month" to the 15th is compared with the 15 days before it: 17–31 August.
		assertThat(sales.comparedFrom()).isEqualTo(LocalDate.parse("2026-08-17"));
		assertThat(sales.comparedTo()).isEqualTo(LocalDate.parse("2026-08-31"));
	}

	/**
	 * "By desk" reads the pipelines a desk is <em>granted</em> (D19b) — the set their board draws — and
	 * not the legacy `ghl_pipeline_id` column, which nothing has written since Unit 44b. So a desk on
	 * two pipelines counts both, and one whose grants were all revoked drops off the card even though
	 * the old column still names a pipeline.
	 */
	@Test
	void aDeskIsTheSetOfPipelinesItIsGrantedNotTheLegacyColumn() {
		TeamMember onTwo = desk(Role.SALES, "legacy-one");
		HELD.put(onTwo.getId(), List.of("pipe-a", "pipe-b"));
		TeamMember revoked = desk(Role.SALES, "legacy-revoked");
		HELD.put(revoked.getId(), List.of());
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND)).willReturn(List.of(onTwo, revoked));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND)).willReturn(List.of());
		given(mirror.onPipelines(List.of("pipe-a")))
				.willReturn(List.of(won("100", at("2026-09-04"), at("2026-09-01"), "Referral")));
		given(mirror.onPipelines(List.of("pipe-b")))
				.willReturn(List.of(won("250", at("2026-09-05"), at("2026-09-02"), "Referral")));

		var overview = service("0").forCaller(window("month"), BRAND);

		assertThat(overview.desks()).singleElement().satisfies((row) -> {
			assertThat(row.won()).isEqualTo(2);
			assertThat(row.wonValue()).isEqualByComparingTo("350");
		});
		verify(mirror, never()).onPipelines(List.of("legacy-one"));
		verify(mirror, never()).onPipelines(List.of("legacy-revoked"));
	}

	/**
	 * "Open now" is what the desk holds today — won and lost deals out — whatever the date range, so it
	 * can be set against the desk's own board header. It is not the date-windowed "New".
	 */
	@Test
	void aDeskReportsWhatItHoldsOpenRightNowWhateverTheRange() {
		givenOneSalesDesk();
		given(mirror.onPipelines(any())).willReturn(List.of(
				mirrorRow("open", "100"), mirrorRow("open", "50"), mirrorRow("won", "900"), mirrorRow("lost", "30")));

		var overview = service("0").forCaller(window("week"), BRAND);

		assertThat(overview.desks()).singleElement().satisfies((row) -> {
			assertThat(row.open()).isEqualTo(2);
			assertThat(row.openValue()).isEqualByComparingTo("150");
			assertThat(row.newLeads()).isZero();
		});
	}

	private static com.ie.evalos.domain.Opportunity mirrorRow(String status, String amount) {
		var row = new com.ie.evalos.domain.Opportunity(BRAND, UUID.randomUUID().toString(), UUID.randomUUID());
		row.syncFromGhl("c", row.getPipelineId(), "s", "Deal", new BigDecimal(amount), status, null, null, null,
				Instant.now(), null, null);
		return row;
	}

	private static com.ie.evalos.domain.Opportunity won(String amount, Instant wonAt, Instant createdAt,
			String source) {
		return deal("won", amount, createdAt, wonAt, source);
	}

	/** A mirrored deal, as `OpportunityMirrorService.onPipelines` hands it back. */
	private static com.ie.evalos.domain.Opportunity deal(String status, String amount, Instant createdAt,
			Instant statusChangedAt, String source) {
		var row = new com.ie.evalos.domain.Opportunity(BRAND, UUID.randomUUID().toString(), UUID.randomUUID());
		row.syncFromGhl("contact-1", row.getPipelineId(), "stage", "A deal", new BigDecimal(amount), status, source,
				null, createdAt, statusChangedAt, statusChangedAt, statusChangedAt);
		return row;
	}

	/**
	 * GHL's API cannot set an opportunity's native Source, so the back-fill writes the Lead Source
	 * custom field. A deal with a blank Source is attributed by it, and one with neither is still
	 * "Unattributed" — the fallback must not turn blanks into a guess.
	 */
	@Test
	void attributesABlankSourceToTheLeadSourceCustomField() {
		givenOneSalesDesk();
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		given(jdbc.query(org.mockito.ArgumentMatchers.contains("ghl_custom_field"),
				org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq(BRAND)))
				.willReturn(List.of("field-lead-source"));
		var withField = won("5000", at("2026-09-04"), at("2026-09-01"), null);
		withField.syncCustomFields(java.util.Map.of("field-lead-source", "Google Ads"));
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(withField,
				won("1000", at("2026-09-05"), at("2026-09-01"), null)));

		var overview = service("0", jdbc).forCaller(window("month"), BRAND);

		assertThat(overview.bySource()).extracting((row) -> row.source())
				.containsExactlyInAnyOrder("Google Ads", "Unattributed");
	}

	/** A desk's own Lead Source beats the contact's source the mirror falls back to. */
	@Test
	void theLeadSourceCustomFieldOutranksTheMirroredSource() {
		givenOneSalesDesk();
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		given(jdbc.query(org.mockito.ArgumentMatchers.contains("ghl_custom_field"),
				org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq(BRAND)))
				.willReturn(List.of("field-lead-source"));
		var deal = won("5000", at("2026-09-04"), at("2026-09-01"), "Website");
		deal.syncCustomFields(java.util.Map.of("field-lead-source", "Referral"));
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(deal));

		var overview = service("0", jdbc).forCaller(window("month"), BRAND);

		assertThat(overview.bySource()).extracting((row) -> row.source()).containsExactly("Referral");
	}

	/**
	 * Two sales desks granted the same pipeline each show its deals on their own row, but the totals count
	 * a deal once — and the pipeline is read once.
	 */
	@Test
	void aPipelineHeldByTwoDesksCountsOnceInTheTotals() {
		TeamMember first = desk(Role.SALES, PIPELINE);
		TeamMember second = desk(Role.SALES, PIPELINE);
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND)).willReturn(List.of(first, second));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND)).willReturn(List.of());
		given(mirror.onPipelines(List.of(PIPELINE)))
				.willReturn(List.of(won("5000", at("2026-09-04"), at("2026-09-02"), "Referral")));

		var overview = service("0").forCaller(window("month"), BRAND);

		assertThat(overview.sales().won()).isEqualTo(1);
		assertThat(overview.sales().newLeads()).isEqualTo(1);
		assertThat(overview.headline().won()).isEqualByComparingTo("5000");
		assertThat(overview.bySource()).singleElement().satisfies((row) -> assertThat(row.deals()).isEqualTo(1));
		assertThat(overview.desks()).hasSize(2).allSatisfy((row) -> assertThat(row.won()).isEqualTo(1));
		verify(mirror, org.mockito.Mockito.times(1)).onPipelines(List.of(PIPELINE));
	}

	/** Today's figures are today's whatever the range: a lead and a win from this morning, one from last week. */
	@Test
	void todaysFiguresCountOnlyToday() {
		givenOneSalesDesk();
		Instant now = Instant.now();
		Instant lastWeek = now.minus(java.time.Duration.ofDays(7));
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(
				deal("open", "300", now, null, "Referral"),
				won("1200", now, lastWeek, "Website"),
				deal("open", "50", lastWeek, null, "Referral")));

		var today = service("0").forCaller(window("year"), BRAND).today();

		assertThat(today.salesLeads()).isEqualTo(1);
		assertThat(today.salesLeadValue()).isEqualByComparingTo("300");
		assertThat(today.won()).isEqualTo(1);
		assertThat(today.wonValue()).isEqualByComparingTo("1200");
		assertThat(today.marketingLeads()).isZero();
		// Won today is won this month too; the week-old lead was never won.
		assertThat(today.monthWon()).isEqualTo(1);
		assertThat(today.monthWonValue()).isEqualByComparingTo("1200");
		// Opened this month: the morning lead, still open. (The week-old ones count only when the month began earlier.)
		assertThat(today.monthSalesLeads()).isGreaterThanOrEqualTo(1);
		assertThat(today.monthOpenLeads()).isGreaterThanOrEqualTo(1);
		assertThat(today.monthOpenLeads()).isLessThanOrEqualTo(today.monthSalesLeads());
	}

	/** The arrows compare today with yesterday: two leads today against one yesterday is +100%; none yesterday, none. */
	@Test
	void todaysArrowsCompareWithYesterday() {
		givenOneSalesDesk();
		Instant now = Instant.now();
		Instant yesterday = LocalDate.now(BusinessCalendar.clock()).minusDays(1).atStartOfDay(BusinessCalendar.ZONE)
				.toInstant().plusSeconds(3600);
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(
				deal("open", "10", now, null, "Referral"),
				deal("open", "10", now, null, "Referral"),
				deal("open", "10", yesterday, null, "Referral")));

		var changes = service("0").forCaller(window("year"), BRAND).today().changes();

		assertThat(changes.salesLeads()).isEqualTo(100);
		assertThat(changes.won()).isNull();
		assertThat(changes.marketingLeads()).isNull();
	}

	private static Instant at(String date) {
		return LocalDate.parse(date).atStartOfDay(BusinessCalendar.ZONE).toInstant();
	}

	/**
	 * A win is placed by when it was won. A deal opened in July and won in September is September's;
	 * if this service ever bucketed wins by creation date instead, this test is what says so — the July
	 * deal would vanish and "won this month" would be silently low by the length of the sales cycle.
	 */
	@Test
	void countsAWinByWhenItWasWonRatherThanWhenItWasOpened() {
		givenOneSalesDesk();
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(
				won("5000", at("2026-09-04"), at("2026-07-02"), "Referral"),
				// Won in the previous window: it feeds the delta and must not feed the total.
				won("1000", at("2026-08-20"), at("2026-08-01"), "Website")));

		var overview = service("0").forCaller(window("month"), BRAND);

		assertThat(overview.sales().won()).isEqualTo(1);
		assertThat(overview.sales().wonValue()).isEqualByComparingTo("5000");
		assertThat(overview.headline().won()).isEqualByComparingTo("5000");
		assertThat(overview.bySource()).singleElement()
				.satisfies((row) -> assertThat(row.source()).isEqualTo("Referral"));
		// 5000 against the previous window's 1000.
		assertThat(overview.sales().wonDeltaPct()).isEqualTo(400);
	}

	/**
	 * The trend is the same question as the headline, cut into slices: this month so far (1-15 Sept) is 15
	 * daily slices, each lined up against the same day of the previous 15 days (17-31 Aug).
	 */
	@Test
	void cutsThePeriodIntoSlicesAndLinesUpThePreviousPeriod() {
		givenOneSalesDesk();
		given(mirror.onPipelines(List.of(PIPELINE))).willReturn(List.of(
				deal("open", "100", at("2026-09-03"), at("2026-09-03"), "Referral"),
				deal("open", "100", at("2026-08-18"), at("2026-08-18"), "Referral"),
				won("5000", at("2026-09-04"), at("2026-07-02"), "Referral"),
				won("1000", at("2026-08-20"), at("2026-08-01"), "Website")));

		var trend = service("0").forCaller(window("month"), BRAND).trend();

		assertThat(trend.comparable()).isTrue();
		assertThat(trend.points()).hasSize(15);
		assertThat(trend.points().get(2).leads()).isEqualTo(1);
		assertThat(trend.points().get(1).previousLeads()).isEqualTo(1);
		assertThat(trend.points().get(3).won()).isEqualByComparingTo("5000");
		assertThat(trend.points().get(3).previousWon()).isEqualByComparingTo("1000");
	}

	/**
	 * A monthly target has exactly one denominator.
	 *
	 * <p>Applying it to a week would print a figure that is arithmetically correct and
	 * commercially meaningless, which is worse than no figure — somebody quotes it.
	 */
	@Test
	void appliesTheMonthlyGoalOnlyToAMonth() {
		givenOneSalesDesk();
		given(mirror.onPipelines(List.of(PIPELINE)))
				.willReturn(List.of(won("27500", at("2026-09-04"), at("2026-09-01"), null)));

		assertThat(service("55000").forCaller(window("month"), BRAND).headline().pctToGoal()).isEqualTo(50);
		assertThat(service("55000").forCaller(window("week"), BRAND).headline().pctToGoal()).isNull();
		assertThat(service("55000").forCaller(window("week"), BRAND).headline().goal()).isNull();
		// No goal configured is not a goal of zero: the tile shows the amount and says so.
		assertThat(service("0").forCaller(window("month"), BRAND).headline().goal()).isNull();
	}

	/**
	 * "Late" means past the promised date, and nothing wider.
	 *
	 * <p>{@code PmMetrics.atRiskNow} sits on the same screen and counts cases merely inside the
	 * red band. Two tiles answering the word "late" with two definitions is how a dashboard stops
	 * being believed, so this one is deliberately the narrow reading.
	 */
	@Test
	void countsOnlyCasesAlreadyPastTheirPromisedDateAsLate() {
		givenOneSalesDesk();

		Case late = caseOf(ServiceType.CREDENTIAL_EVALUATION, "800", Stage.DRAFT_IN_PROGRESS);
		late.setDeadline(Instant.now().minusSeconds(3600));
		Case soon = caseOf(ServiceType.CREDENTIAL_EVALUATION, "800", Stage.DRAFT_IN_PROGRESS);
		soon.setDeadline(Instant.now().plusSeconds(3600));
		Case deliveredLate = caseOf(ServiceType.CREDENTIAL_EVALUATION, "800", Stage.DELIVERED);
		deliveredLate.setDeadline(Instant.now().minusSeconds(7200));
		// **`CLOCK.instant()`, not `Instant.now()`, and the difference is a day-shaped bug.**
		// `delivered` counts a delivery date inside the WINDOW, and the window comes from the fixed
		// clock — 1-15 September, exclusive end at midnight opening the 16th. A real `now()` sat
		// inside that only while the machine's date was still the 15th, so this assertion passed on
		// the day it was written and failed on every run afterwards. The deadlines above stay on the
		// real clock deliberately: `GmOverviewService.evaluation` compares them against its own
		// `Instant.now()`, which no caller can inject.
		deliveredLate.setDeliveryDate(CLOCK.instant());
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(late, soon, deliveredLate));

		var evaluation = service("0").forCaller(window("month"), BRAND).evaluation();

		// The delivered-late case is history, not a queue: it counts as delivered and not as late.
		assertThat(evaluation.late()).isEqualTo(1);
		assertThat(evaluation.openCases()).isEqualTo(2);
		assertThat(evaluation.delivered()).isEqualTo(1);
		assertThat(evaluation.deliveredValue()).isEqualByComparingTo("800");
	}

	private static Case caseOf(ServiceType service, String value, Stage stage) {
		Case subject = new Case(BRAND, "IE-" + UUID.randomUUID(), stage);
		subject.setServiceType(service);
		subject.setDealValue(new BigDecimal(value));
		subject.setPaid(true);
		return subject;
	}

}
