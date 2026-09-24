package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.OpportunityBoardService;

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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach the board, and the claim that no request can widen it.
 *
 * <p>The route takes <strong>no pipeline parameter</strong>, which is the whole access model: what
 * a caller sees is decided by their own principal. {@link #thereIsNoParameterThatCanNameAPipeline}
 * is the assertion that keeps it that way.
 */
@WebMvcTest(controllers = OpportunityBoardController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class OpportunityBoardControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final OpportunityBoardService.Board BOARD = new OpportunityBoardService.Board(
			List.of(new OpportunityBoardService.BoardColumn("s1", "New", 0,
					List.of(new OpportunityBoardService.Deal("o1", "Acme Corp", "contact_1", "open",
							new BigDecimal("1200"), Instant.parse("2026-09-01T09:00:00Z"), "Website",
							"Credential evaluation")),
					new BigDecimal("1200"))),
			1, new BigDecimal("1200"), Instant.parse("2026-09-10T09:00:00Z"), false, true);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	OpportunityBoardService board;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Desk", role,
				role == Role.GM ? null : BRAND_IE, null, "pipe_mine", null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void aSalesCallerGetsTheirBoard() throws Exception {
		given(board.forCaller()).willReturn(BOARD);

		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.columns[0].stageName").value("New"))
				.andExpect(jsonPath("$.data.columns[0].deals[0].name").value("Acme Corp"))
				.andExpect(jsonPath("$.data.totalDeals").value(1));
	}

	@Test
	void marketingReachesTheSameRoute() throws Exception {
		given(board.forCaller()).willReturn(BOARD);

		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING)))
				.andExpect(status().isOk());
	}

	@Test
	void theGmReachesItForTheUnion() throws Exception {
		given(board.forCaller()).willReturn(BOARD);

		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());
	}

	/**
	 * Every role that is not on the desk.
	 *
	 * <p><strong>EXCLUDE, not INCLUDE, and the difference is the whole point.</strong> Listing
	 * the refused roles by name would mean a role added tomorrow is asserted about by nobody —
	 * the same flaw this unit had to fix in {@code navigation.test.ts}, where a hardcoded list of
	 * three GHL paths let a fourth one through silently. Naming the three that *may* pass makes
	 * every future role refused by default, which is the safe direction.
	 *
	 * <p>The Expert Network Manager is the one worth noticing among today's: {@code Tier.SUPPLY}
	 * reads its whole brand, so "reads a lot already" is exactly the argument that would let this
	 * screen leak — and that tier has nothing to do with GHL.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE,
			names = { "SALES", "MARKETING", "GM" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());

		then(board).should(never()).forCaller();
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/opportunities/board")).andExpect(status().isUnauthorized());
	}

	/**
	 * <strong>No parameter can name a pipeline.</strong>
	 *
	 * <p>The service takes no argument, so there is nowhere for a caller-supplied pipeline to go.
	 * Asserted by calling the route with one anyway and checking the service is still invoked
	 * with nothing — the day somebody adds `@RequestParam String pipelineId` to "help the GM
	 * filter", this fails and they have to come and argue for it.
	 */
	@Test
	void thereIsNoParameterThatCanNameAPipeline() throws Exception {
		given(board.forCaller()).willReturn(BOARD);

		mockMvc.perform(get("/api/opportunities/board")
				.param("pipelineId", "pipe_someone_else")
				.param("brandId", UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(status().isOk());

		then(board).should().forCaller();
	}

	/** GHL refusing is a 502, as everywhere else this location is read. */
	@Test
	void ghlBeingUnavailableIsABadGateway() throws Exception {
		willThrow(new GhlUnavailableException("GHL refused the request with HTTP 401"))
				.given(board).forCaller();

		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(status().isBadGateway());
	}

	/** The payload carries no cache bookkeeping — a screen shows deals, not `fetchedAt` per row. */
	@Test
	void theDealPayloadCarriesNoCacheInternals() throws Exception {
		given(board.forCaller()).willReturn(BOARD);

		mockMvc.perform(get("/api/opportunities/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(jsonPath("$.data.columns[0].deals[0].fetchedAt").doesNotExist())
				.andExpect(jsonPath("$.data.columns[0].deals[0].pipelineId").doesNotExist());
	}

}
