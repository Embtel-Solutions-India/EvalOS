package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.CaseManagerMetricsService;
import com.ie.evalos.service.CaseManagerWorkService;
import com.ie.evalos.service.CoordinatorMetricsService;
import com.ie.evalos.service.DraftReviewService;
import com.ie.evalos.service.ExpertNetworkMetricsService;
import com.ie.evalos.service.GmOverviewService;
import com.ie.evalos.service.NavBadgeService;
import com.ie.evalos.service.PipelineJourneyService;
import com.ie.evalos.service.PmMetricsService;
import com.ie.evalos.service.PmOverviewService;
import com.ie.evalos.service.RevenueMetricsService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** `/case-manager/work` is open to exactly the roles `/case-manager` is — the CM's own docket, nobody else's. */
@WebMvcTest(controllers = MetricsController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class CaseManagerWorkRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final CaseManagerWorkService.CaseManagerWork EMPTY = new CaseManagerWorkService.CaseManagerWork(
			new CaseManagerWorkService.Checklist(0, 0, List.of()),
			new CaseManagerWorkService.Offers(0, 0, List.of()),
			new CaseManagerWorkService.Drafts(0, 0, 0, 0, 0, 0));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	CaseManagerWorkService work;

	@MockitoBean
	PmOverviewService pmOverview;

	@MockitoBean
	GmOverviewService gmOverview;

	@MockitoBean
	PipelineJourneyService journey;

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

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "CASE_MANAGER" })
	void workIsOpenToTheSameRolesAsTheCmDocket(Role role) throws Exception {
		given(work.forCaller()).willReturn(EMPTY);
		mockMvc.perform(get("/api/metrics/case-manager/work").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = { "GM", "CASE_MANAGER" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/metrics/case-manager/work").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
		then(work).should(never()).forCaller();
	}
}
