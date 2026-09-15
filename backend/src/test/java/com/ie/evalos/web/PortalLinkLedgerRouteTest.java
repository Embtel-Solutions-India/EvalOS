package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read the portal-links ledger (G16), and what it will not accept.
 *
 * <p>{@link #theExpertNetworkManagerIsRefused} is the one worth reading: they are on four other
 * metrics routes, so their absence here looks like an oversight and is not.
 */
@WebMvcTest(controllers = MetricsController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class PortalLinkLedgerRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final PortalLinkLedgerService.Summary SUMMARY = new PortalLinkLedgerService.Summary(
			1, 0,
			List.of(new PortalLinkLedgerService.LedgerRow(UUID.randomUUID(), "IE-2026-0001",
					Stage.EXPERT_SIGNING, PortalAudience.EXPERT,
					PortalLinkLedgerService.LinkState.RED, false, null, Instant.parse("2026-09-20T09:00:00Z"),
					0, true)));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	PortalLinkLedgerService ledger;

	@MockitoBean
	GmOverviewService gmOverview;

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

	/** The four roles who can act on a red row, plus the GM. */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE,
			names = { "GM", "BRAND_MANAGER", "PROJECT_MANAGER", "PROJECT_COORDINATOR", "CASE_MANAGER" })
	void theRolesWhoCanActOnItCanReadIt(Role role) throws Exception {
		given(ledger.summaryForCaller()).willReturn(SUMMARY);

		mockMvc.perform(get("/api/metrics/portal-links").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.red").value(1))
				.andExpect(jsonPath("$.data.rows[0].state").value("RED"));
	}

	/**
	 * <strong>The Expert Network Manager is refused, and it is not an oversight.</strong>
	 *
	 * <p>They are on four other metrics routes, so leaving them off this one looks like a
	 * mistake. It is the axis {@code architecture.md} draws: {@code Tier.SUPPLY} reads the
	 * roster, not case content — and every row here names a case. They are notified about
	 * expert signing and support it; they do not read the case list to do so.
	 */
	@Test
	void theExpertNetworkManagerIsRefused() throws Exception {
		mockMvc.perform(get("/api/metrics/portal-links")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isForbidden());

		then(ledger).should(never()).summaryForCaller();
	}

	/** Sales and Marketing have no case work at all, so nothing here concerns them. */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE, names = { "SALES", "MARKETING" })
	void theGhlDesksAreRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/metrics/portal-links").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/metrics/portal-links")).andExpect(status().isUnauthorized());
	}

	/**
	 * No parameter narrows this, and none should.
	 *
	 * <p>The rows are whatever the caller's own scoped case list already contains, so a
	 * {@code brandId} would either do nothing or be a way to ask about somebody else's brand.
	 */
	@Test
	void noParameterCanWidenOrNarrowIt() throws Exception {
		given(ledger.summaryForCaller()).willReturn(SUMMARY);

		mockMvc.perform(get("/api/metrics/portal-links")
				.param("brandId", UUID.randomUUID().toString())
				.param("caseId", UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isOk());

		then(ledger).should().summaryForCaller();
		then(ledger).shouldHaveNoMoreInteractions();
	}

	/**
	 * The payload says when a link was opened and never when it was sent.
	 *
	 * <p>EvalOS cannot observe a staff member pasting a URL into a mail client. A field claiming
	 * otherwise would be reported by this very screen as if it were true.
	 */
	@Test
	void thePayloadCarriesOpenedNeverSent() throws Exception {
		given(ledger.summaryForCaller()).willReturn(SUMMARY);

		mockMvc.perform(get("/api/metrics/portal-links")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(jsonPath("$.data.rows[0].openedAt").doesNotExist())
				.andExpect(jsonPath("$.data.rows[0].sentAt").doesNotExist())
				.andExpect(jsonPath("$.data.rows[0].mintedBy").doesNotExist())
				.andExpect(jsonPath("$.data.rows[0].needed").value(true));
	}
}
