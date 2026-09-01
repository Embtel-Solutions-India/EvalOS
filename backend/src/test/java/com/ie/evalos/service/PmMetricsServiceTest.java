package com.ie.evalos.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.PmMetricsService.PmMetrics;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The PM's figures, and specifically the places where a plausible-looking number would be the
 * wrong one: an empty denominator, a case counted at the wrong stage, a Case Manager missing
 * from a list because they happen to be idle.
 */
class PmMetricsServiceTest {

	private static final UUID BRAND_IE = UUID.randomUUID();
	private static final UUID BRAND_XP = UUID.randomUUID();
	private static final UUID SARAH = UUID.randomUUID();
	private static final UUID DEV = UUID.randomUUID();

	/** Monday 6 July 2026, 09:00 PT — the clean business week `DeadlineRiskCalculatorTest` uses. */
	private static final Instant NOW = Instant.now();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final TeamMemberQueryService members = mock(TeamMemberQueryService.class);
	private final BusinessCalendar calendar = new BusinessCalendar();
	private final PmMetricsService metrics = new PmMetricsService(lifecycle, members,
			new DeadlineRiskCalculator(calendar), calendar, 12);

	private static Case aCase(UUID brandId, String code, Stage stage) {
		return new Case(brandId, code, stage);
	}

	/**
	 * {@code created_at} is stamped by {@code ScopedEntity}'s {@code @PrePersist} and has no
	 * setter, deliberately — a row never changes when it was created. Reflection is the least
	 * bad way to pin it for a unit test: the alternative is adding a production setter that
	 * exists only for tests, which would weaken the immutability the entity is asserting.
	 */
	private static Case createdAt(Case subject, Instant when) {
		ReflectionTestUtils.setField(subject, "createdAt", when);
		return subject;
	}

	private static Instant pt(int year, int month, int day, int hour) {
		return LocalDateTime.of(year, month, day, hour, 0).atZone(BusinessCalendar.ZONE).toInstant();
	}

	/**
	 * Stubs the roster in one call.
	 *
	 * <p>The member mocks are built <em>before</em> {@code assignable} is stubbed, and that
	 * ordering is the point: calling a {@code given(...)} while another one's argument is still
	 * being evaluated leaves Mockito with an unfinished stubbing and fails the next unrelated
	 * test in the class.
	 */
	private void givenCaseManagers(UUID... ids) {
		List<TeamMember> roster = new java.util.ArrayList<>();
		for (UUID id : ids) {
			TeamMember member = mock(TeamMember.class);
			given(member.getId()).willReturn(id);
			given(member.getDisplayName()).willReturn(id == SARAH ? "Sarah" : "Dev");
			roster.add(member);
		}
		given(members.assignable(Role.CASE_MANAGER)).willReturn(roster);
	}

	private PmMetrics compute(List<Case> cases, UUID brandFilter) {
		given(lifecycle.list(any(), any(), any())).willReturn(cases);
		return metrics.forCaller(NOW.minus(30, ChronoUnit.DAYS), NOW, brandFilter);
	}

	// --- on-time delivery ----------------------------------------------------

	@Test
	void theOnTimeRateCarriesTheDenominatorItWasTakenOver() {
		Case early = aCase(BRAND_IE, "IE-1", Stage.CLOSED);
		early.setDeadline(NOW.minus(3, ChronoUnit.DAYS));
		early.setDeliveryDate(NOW.minus(5, ChronoUnit.DAYS));

		Case late = aCase(BRAND_IE, "IE-2", Stage.CLOSED);
		late.setDeadline(NOW.minus(9, ChronoUnit.DAYS));
		late.setDeliveryDate(NOW.minus(4, ChronoUnit.DAYS));

		var onTime = compute(List.of(early, late), null).onTime();

		assertThat(onTime.delivered()).isEqualTo(2);
		assertThat(onTime.onTime()).isEqualTo(1);
		assertThat(onTime.ratePct()).isEqualTo(50);
	}

	/**
	 * The distinction the whole tile turns on: nothing delivered is not the same as nothing
	 * delivered on time. A 0% here would accuse a team of a failure that did not happen.
	 */
	@Test
	void anEmptyPeriodHasNoRateRatherThanAZeroRate() {
		var onTime = compute(List.of(aCase(BRAND_IE, "IE-1", Stage.DRAFT_IN_PROGRESS)), null).onTime();

		assertThat(onTime.delivered()).isZero();
		assertThat(onTime.ratePct()).isNull();
		assertThat(onTime.deltaPoints()).isNull();
	}

	/** A case nobody promised a date for cannot have missed it. */
	@Test
	void aDeliveredCaseWithNoDeadlineCountsAsOnTime() {
		Case undated = aCase(BRAND_IE, "IE-1", Stage.CLOSED);
		undated.setDeliveryDate(NOW.minus(2, ChronoUnit.DAYS));

		assertThat(compute(List.of(undated), null).onTime().ratePct()).isEqualTo(100);
	}

	// --- at risk right now ---------------------------------------------------

	/**
	 * A case already at the delivery step is not "at risk of missing its deadline" in any sense
	 * the PM can act on — the work is done and the Coordinator is sending it. Counting it would
	 * put permanent amber on a tile whose whole job is to be actionable.
	 */
	@Test
	void aCaseAtTheDeliveryStepIsNotCountedAsAtRisk() {
		Case delivering = aCase(BRAND_IE, "IE-1", Stage.READY_TO_DELIVER);
		delivering.setDeadline(NOW.plus(2, ChronoUnit.HOURS));

		Case drafting = aCase(BRAND_IE, "IE-2", Stage.DRAFT_IN_PROGRESS);
		drafting.setDeadline(NOW.plus(2, ChronoUnit.HOURS));

		assertThat(compute(List.of(delivering, drafting), null).atRiskNow()).isEqualTo(1);
	}

	// --- unassigned ----------------------------------------------------------

	@Test
	void unassignedCountsWhatIsStillInThePool() {
		Case pooled = aCase(BRAND_IE, "IE-1", Stage.PM_REVIEW);
		pooled.setPoolStatus(PoolStatus.IN_POOL);
		Case taken = aCase(BRAND_IE, "IE-2", Stage.PM_REVIEW);
		taken.setPoolStatus(PoolStatus.ASSIGNED);

		assertThat(compute(List.of(pooled, taken), null).unassigned()).isEqualTo(1);
	}

	// --- revision rate -------------------------------------------------------

	@Test
	void theRevisionRateIsTheShareOfCasesThatNeededMoreThanOneDraft() {
		givenCaseManagers(SARAH);

		Case clean = aCase(BRAND_IE, "IE-1", Stage.DRAFT_IN_PROGRESS);
		clean.setAssignedCm(SARAH);
		clean.setDraftVersionCount(1);

		Case revised = aCase(BRAND_IE, "IE-2", Stage.DRAFT_IN_PROGRESS);
		revised.setAssignedCm(SARAH);
		revised.setDraftVersionCount(3);

		var rates = compute(List.of(clean, revised), null).revisionRateByCm();

		assertThat(rates).singleElement().satisfies(rate -> {
			assertThat(rate.name()).isEqualTo("Sarah");
			assertThat(rate.cases()).isEqualTo(2);
			assertThat(rate.revised()).isEqualTo(1);
			assertThat(rate.ratePct()).isEqualTo(50);
		});
	}

	// --- workload ------------------------------------------------------------

	/**
	 * An idle Case Manager has to appear, or the redistribution workflow cannot show anywhere to
	 * move a case *to*. A list of only the busy people is a list with no answer on it.
	 */
	@Test
	void aCaseManagerHoldingNothingStillAppearsOnTheWorkloadList() {
		givenCaseManagers(SARAH, DEV);

		Case held = aCase(BRAND_IE, "IE-1", Stage.DRAFT_IN_PROGRESS);
		held.setAssignedCm(SARAH);

		var workload = compute(List.of(held), null).workload();

		assertThat(workload).hasSize(2);
		assertThat(workload).filteredOn(row -> row.name().equals("Dev"))
				.singleElement()
				.satisfies(row -> {
					assertThat(row.active()).isZero();
					assertThat(row.capacity()).isEqualTo(12);
				});
	}

	/** Capacity is about work in front of somebody, and a closed case is not that. */
	@Test
	void aClosedCaseDoesNotCountAgainstCapacity() {
		givenCaseManagers(SARAH);

		Case done = aCase(BRAND_IE, "IE-1", Stage.CLOSED);
		done.setAssignedCm(SARAH);

		assertThat(compute(List.of(done), null).workload()).singleElement()
				.satisfies(row -> assertThat(row.active()).isZero());
	}

	// --- completion by service type ------------------------------------------

	/**
	 * The median, not the mean, and on business hours. Three cases at 8, 16 and 400 business
	 * hours have a median of 16 and a mean of 141 — the mean describes none of them, and the one
	 * that stalled would make a healthy product look broken.
	 */
	@Test
	void completionUsesTheMedianSoOneStalledCaseCannotDistortTheProduct() {
		List<Case> cases = List.of(
				delivered("IE-1", pt(2026, 7, 6, 9), pt(2026, 7, 6, 17)),
				delivered("IE-2", pt(2026, 7, 6, 9), pt(2026, 7, 7, 17)),
				delivered("IE-3", pt(2026, 7, 6, 9), pt(2026, 9, 30, 17)));

		given(lifecycle.list(any(), any(), any())).willReturn(cases);
		var completion = metrics.forCaller(pt(2026, 1, 1, 9), pt(2026, 12, 31, 17), null)
				.completionByService();

		assertThat(completion).singleElement().satisfies(row -> {
			assertThat(row.serviceType()).isEqualTo(ServiceType.CREDENTIAL_EVALUATION);
			assertThat(row.delivered()).isEqualTo(3);
			assertThat(row.medianBusinessHours()).isEqualTo(16);
		});
	}

	private static Case delivered(String code, Instant created, Instant deliveredAt) {
		Case subject = createdAt(aCase(BRAND_IE, code, Stage.CLOSED), created);
		subject.setServiceType(ServiceType.CREDENTIAL_EVALUATION);
		subject.setDeliveryDate(deliveredAt);
		return subject;
	}

	// --- scope ---------------------------------------------------------------

	/**
	 * The brand parameter can only ever narrow. Asserted because it is the one value here that
	 * arrives from a query string.
	 */
	@Test
	void theBrandFilterNarrowsAndNeverWidens() {
		Case ie = aCase(BRAND_IE, "IE-1", Stage.PM_REVIEW);
		ie.setPoolStatus(PoolStatus.IN_POOL);
		Case xp = aCase(BRAND_XP, "XP-1", Stage.PM_REVIEW);
		xp.setPoolStatus(PoolStatus.IN_POOL);

		assertThat(compute(List.of(ie, xp), null).unassigned()).isEqualTo(2);
		assertThat(compute(List.of(ie, xp), BRAND_IE).unassigned()).isEqualTo(1);
	}
}
