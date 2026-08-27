package com.ie.evalos.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertNetworkMetricsService.ExpertNetworkMetrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@code expert.availability} is nullable (V7) and the sheet import need not set it, so a real
 * roster contains rows with no availability recorded. Both readers here take that value as an
 * {@link java.util.EnumMap} key and as a {@code switch} subject, either of which throws on null
 * rather than counting a zero — so the whole endpoint used to 500 on one unset row.
 */
class ExpertNetworkMetricsServiceTest {

	private static final UUID BRAND_IE = UUID.randomUUID();

	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final ExpertLoadService loads = mock(ExpertLoadService.class);

	private final ExpertNetworkMetricsService metrics =
			new ExpertNetworkMetricsService(experts, offers, loads, 5);

	@BeforeEach
	void anEnm() {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "enm@evalos.local", "Staff",
				Role.EXPERT_NETWORK_MANAGER, BRAND_IE, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		given(loads.forExperts(anyCollection())).willReturn(Map.of());
		given(offers.countOutcomesPerExpert(any(), anyCollection())).willReturn(List.of());
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anExpertWithNoAvailabilityRecordedCountsAsInactiveInsteadOfCrashing() {
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(
				expert("Free Person", Availability.AVAILABLE),
				expert("Nobody Said", null)));

		ExpertNetworkMetrics result = metrics.forCaller();

		assertThat(result.roster().available()).isEqualTo(1);
		assertThat(result.roster().inactive()).isEqualTo(1);
		assertThat(result.roster().total()).isEqualTo(2);
		// Same row, second reader: it is counted against its field, not dropped or thrown on.
		assertThat(result.coverage()).singleElement()
				.satisfies(row -> {
					assertThat(row.field()).isEqualTo(FieldTag.LAW);
					assertThat(row.available()).isEqualTo(1);
					assertThat(row.inactive()).isEqualTo(1);
					assertThat(row.total()).isEqualTo(2);
				});
	}

	/**
	 * The row shape the dev database actually holds: 69 of 79 experts were written straight through
	 * {@code ExpertRepository.save} by {@code LocalPostgresIntegrationTest}, which bypasses
	 * {@code ExpertService.apply} and so sets no availability, no fields, no score, no onboarding
	 * date. Availability was the only one of those the endpoint failed to guard, and asserting the
	 * bare row keeps the other three honest — a name and nothing else has to produce figures rather
	 * than a 500.
	 */
	@Test
	void anExpertWithNothingButANameProducesFiguresRatherThanA500() {
		Expert bare = new Expert(BRAND_IE, "Nothing But A Name");
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(bare));

		ExpertNetworkMetrics result = metrics.forCaller();

		assertThat(result.roster()).isEqualTo(
				new ExpertNetworkMetricsService.RosterHealth(0, 0, 0, 1, 1));
		// No primary field claimed, so no field is reported as covered — not a null row in the list.
		assertThat(result.coverage()).isEmpty();
		// Unscored is unassessed, not low quality; undated is not onboarded this month.
		assertThat(result.lowQuality()).isEmpty();
		assertThat(result.onboarding().thisMonth()).isZero();
	}

	private static Expert expert(String name, Availability availability) {
		Expert expert = new Expert(BRAND_IE, name);
		expert.setPrimaryFields(List.of(FieldTag.LAW));
		expert.setAvailability(availability);
		return expert;
	}
}
