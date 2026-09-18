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
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The GM overview, and specifically the three ways it could quietly report a wrong number.
 *
 * <p>A win placed by the wrong timestamp, a monthly goal applied to a window that is not a month,
 * and a GHL outage taken as a month with no business in it.
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
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private GmOverviewService service(String goal) {
		return new GmOverviewService(lifecycle, teamMembers, ghl, new SellingBrand(BRAND), new BigDecimal(goal), 180);
	}

	private static DateWindow window(String range) {
		return DateWindow.of(range, null, null, CLOCK);
	}

	private void givenOneSalesDesk() {
		TeamMember desk = new TeamMember() {
		};
		ReflectionTestUtils.setField(desk, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(desk, "role", Role.SALES);
		ReflectionTestUtils.setField(desk, "brandId", BRAND);
		ReflectionTestUtils.setField(desk, "displayName", "Nischay");
		ReflectionTestUtils.setField(desk, "ghlPipelineId", PIPELINE);
		ReflectionTestUtils.setField(desk, "active", true);
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND)).willReturn(List.of(desk));
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.MARKETING, BRAND)).willReturn(List.of());
	}

	private static GhlPipelineClient.Opportunity won(String amount, Instant wonAt, Instant createdAt,
			String source) {
		return new GhlPipelineClient.Opportunity(UUID.randomUUID().toString(), "A deal", "contact-1",
				PIPELINE, "stage", "won", new BigDecimal(amount), source, null, createdAt, wonAt,
				wonAt, wonAt);
	}

	private static Instant at(String date) {
		return LocalDate.parse(date).atStartOfDay(BusinessCalendar.ZONE).toInstant();
	}

	/**
	 * The figure the whole lookback exists for.
	 *
	 * <p>GHL's opportunity search filters on {@code createdAt} and offers no filter on the
	 * status-change date, so a deal opened in July and won in September is outside every window
	 * that asks for September's creations. If this service ever bucketed wins by creation date
	 * instead, this test is what says so — the July deal would vanish and "won this month" would
	 * be silently low by the length of the sales cycle.
	 */
	@Test
	void countsAWinByWhenItWasWonRatherThanWhenItWasOpened() {
		givenOneSalesDesk();
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any())).willReturn(List.of());
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any(), eq("won"))).willReturn(List.of(
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
	 * A monthly target has exactly one denominator.
	 *
	 * <p>Applying it to a week would print a figure that is arithmetically correct and
	 * commercially meaningless, which is worse than no figure — somebody quotes it.
	 */
	@Test
	void appliesTheMonthlyGoalOnlyToAMonth() {
		givenOneSalesDesk();
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any())).willReturn(List.of());
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any(), eq("won")))
				.willReturn(List.of(won("27500", at("2026-09-04"), at("2026-09-01"), null)));

		assertThat(service("55000").forCaller(window("month"), BRAND).headline().pctToGoal()).isEqualTo(50);
		assertThat(service("55000").forCaller(window("week"), BRAND).headline().pctToGoal()).isNull();
		assertThat(service("55000").forCaller(window("week"), BRAND).headline().goal()).isNull();
		// No goal configured is not a goal of zero: the tile shows the amount and says so.
		assertThat(service("0").forCaller(window("month"), BRAND).headline().goal()).isNull();
	}

	/**
	 * GHL being unreachable is not a month with no business in it.
	 *
	 * <p>The production half is EvalOS's own rows and stays true regardless, so the payload is a
	 * 200 that names the failure rather than a 502 that takes four working tiles down with the
	 * four that broke.
	 */
	@Test
	void servesTheProductionHalfWhenGhlIsDown() {
		givenOneSalesDesk();
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any()))
				.willThrow(new GhlUnavailableException("GHL did not answer"));
		Case open = caseOf(ServiceType.EXPERT_OPINION_LETTER, "1200", Stage.DRAFT_IN_PROGRESS);
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(open));

		var overview = service("55000").forCaller(window("month"), BRAND);

		assertThat(overview.pipelineUnavailable()).isEqualTo("GHL did not answer");
		assertThat(overview.headline()).isNull();
		assertThat(overview.sales()).isNull();
		assertThat(overview.evaluation().openCases()).isEqualTo(1);
		assertThat(overview.byService()).singleElement()
				.satisfies((row) -> assertThat(row.openValue()).isEqualByComparingTo("1200"));
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
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any())).willReturn(List.of());
		given(ghl.opportunitiesIn(eq(PIPELINE), any(), any(), eq("won"))).willReturn(List.of());

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
