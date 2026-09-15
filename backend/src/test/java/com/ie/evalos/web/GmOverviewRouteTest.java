package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.CaseManagerMetricsService;
import com.ie.evalos.service.CoordinatorMetricsService;
import com.ie.evalos.service.DraftReviewService;
import com.ie.evalos.service.ExpertNetworkMetricsService;
import com.ie.evalos.service.GmOverviewService;
import com.ie.evalos.service.NavBadgeService;
import com.ie.evalos.service.PmMetricsService;
import com.ie.evalos.service.PortalLinkLedgerService;
import com.ie.evalos.service.RevenueMetricsService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read the GM overview.
 *
 * <p><strong>The Brand Manager's refusal is the whole reason this file exists</strong>, and it is
 * the one that looks like a mistake. They are on every other money route here — {@code /revenue},
 * {@code /pm}, {@code /drafts} — because those are brand-scoped. This one is not: half of it
 * reads the GHL location named by {@code evalos.ghl.location-id}, which EvalOS cannot attribute
 * to any brand. {@code architecture.md} licenses that as invariant 1's one exception **on the
 * condition that the reader is the cross-brand role**, because a brand-locked reader cannot tell
 * — and neither can the server — whether the figure is theirs. Widening this gate voids the
 * exception's own argument.
 */
@WebMvcTest(controllers = MetricsController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class GmOverviewRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final GmOverviewService.GmOverview OVERVIEW = new GmOverviewService.GmOverview(
			new GmOverviewService.Headline(new BigDecimal("38400"), new BigDecimal("55000"), 70),
			List.of(new GmOverviewService.SourceRow("Referral", 3, new BigDecimal("14000"))),
			List.of(),
			new GmOverviewService.Sales(142, new BigDecimal("61000"), 9, new BigDecimal("22000"), 12),
			List.of(), new GmOverviewService.Marketing(61, new BigDecimal("12000"), 61),
			new GmOverviewService.Evaluation(34, new BigDecimal("28000"), 3, 28, new BigDecimal("41000")),
			Instant.parse("2026-09-15T09:00:00Z"), null);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	GmOverviewService gmOverview;

	@MockitoBean
	PortalLinkLedgerService ledger;

	@MockitoBean
	PmMetricsService pm;

	@MockitoBean
	CoordinatorMetricsService coordinator;

	@MockitoBean
	CaseManagerMetricsService caseManager;

	@MockitoBean
	ExpertNetworkMetricsService network;

	@MockitoBean
	RevenueMetricsService revenue;

	@MockitoBean
	NavBadgeService navBadges;

	@MockitoBean
	DraftReviewService drafts;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theGmReadsIt() throws Exception {
		given(gmOverview.forCaller(any(), any())).willReturn(OVERVIEW);

		mockMvc.perform(get("/api/metrics/gm").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.headline.pctToGoal").value(70))
				.andExpect(jsonPath("$.data.sales.newLeads").value(142))
				.andExpect(jsonPath("$.data.bySource[0].source").value("Referral"))
				.andExpect(jsonPath("$.data.evaluation.late").value(3));
	}

	/** Every other staff role, the Brand Manager included. See this class's note. */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/metrics/gm").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());

		then(gmOverview).should(never()).forCaller(any(), any());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/metrics/gm")).andExpect(status().isUnauthorized());
	}

	/**
	 * {@code from} and {@code to} are refused on a named range rather than ignored.
	 *
	 * <p>Inherited from {@link com.ie.evalos.common.DateWindow}, asserted here because a window
	 * that silently ignores half its parameters is how a GM reads September's figures under an
	 * August heading.
	 */
	@Test
	void refusesADateOnANamedRange() throws Exception {
		mockMvc.perform(get("/api/metrics/gm").param("range", "month").param("from", "2026-09-01")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isBadRequest());

		then(gmOverview).should(never()).forCaller(any(), any());
	}

}
