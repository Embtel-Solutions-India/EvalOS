package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.CaseBoardService;
import com.ie.evalos.service.CaseBoardService.BoardRow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit 08's board endpoint. The scope itself is {@code ScopePredicate}'s job and is
 * asserted there — what this covers is the shaping the board depends on: a case lands in
 * one place only, every column and lane exists even when empty, and the deal value obeys
 * the same role gate the case list uses.
 */
@WebMvcTest(controllers = CaseBoardController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class CaseBoardControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TEAM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	CaseBoardService board;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, TEAM, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private static BoardRow row(String code, Stage stage, ExceptionState exception, String deadline) {
		Case subject = new Case(BRAND_IE, code, stage);
		subject.setExceptionState(exception);
		subject.setDealValue(new BigDecimal("1450.00"));
		if (deadline != null) {
			subject.setDeadline(Instant.parse(deadline));
		}
		return new BoardRow(subject, "Anita Rao");
	}

	@Test
	void everyColumnAndLaneIsPresentEvenWithNothingOnTheBoard() throws Exception {
		given(board.forCaller(any(), any())).willReturn(List.of());

		mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				// An absent column would make the client drop it; an empty one draws.
				.andExpect(jsonPath("$.data.stages.DOC_COLLECTION").isArray())
				.andExpect(jsonPath("$.data.stages.EXPERT_ASSIGNMENT").isArray())
				.andExpect(jsonPath("$.data.stages.DRAFT_GENERATION").isArray())
				.andExpect(jsonPath("$.data.stages.EXPERT_SIGNING").isArray())
				.andExpect(jsonPath("$.data.stages.FINAL_DELIVERY").isArray())
				.andExpect(jsonPath("$.data.stages.CLOSED").doesNotExist())
				.andExpect(jsonPath("$.data.exceptions.ON_HOLD_AWAITING_CLIENT").isArray())
				.andExpect(jsonPath("$.data.exceptions.EXPERT_DECLINED_REMATCHING").isArray())
				.andExpect(jsonPath("$.data.exceptions.REFUND_REQUESTED").isArray())
				.andExpect(jsonPath("$.data.exceptions.NONE").doesNotExist());
	}

	@Test
	void aCaseHoldingAnExceptionIsInItsLaneAndNotAlsoInItsStageColumn() throws Exception {
		given(board.forCaller(any(), any())).willReturn(List.of(
				row("IE-2026-0001", Stage.DOC_COLLECTION, ExceptionState.NONE, null),
				row("IE-2026-0002", Stage.DOC_COLLECTION, ExceptionState.ON_HOLD_AWAITING_CLIENT, null)));

		mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				// A case on hold is not being worked, so it is not in the column too.
				.andExpect(jsonPath("$.data.stages.DOC_COLLECTION.length()").value(1))
				.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].caseCode").value("IE-2026-0001"))
				.andExpect(jsonPath("$.data.exceptions.ON_HOLD_AWAITING_CLIENT.length()").value(1))
				.andExpect(jsonPath("$.data.exceptions.ON_HOLD_AWAITING_CLIENT[0].caseCode").value("IE-2026-0002"));
	}

	@Test
	void cardsAreOrderedByDeadlineWithUndatedOnesLast() throws Exception {
		given(board.forCaller(any(), any())).willReturn(List.of(
				row("IE-2026-LATE", Stage.DRAFT_GENERATION, ExceptionState.NONE, "2026-09-01T17:00:00Z"),
				row("IE-2026-NONE", Stage.DRAFT_GENERATION, ExceptionState.NONE, null),
				row("IE-2026-SOON", Stage.DRAFT_GENERATION, ExceptionState.NONE, "2026-08-01T17:00:00Z")));

		mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.stages.DRAFT_GENERATION[0].caseCode").value("IE-2026-SOON"))
				.andExpect(jsonPath("$.data.stages.DRAFT_GENERATION[1].caseCode").value("IE-2026-LATE"))
				.andExpect(jsonPath("$.data.stages.DRAFT_GENERATION[2].caseCode").value("IE-2026-NONE"));
	}

	/** Acceptance criterion: a Case Manager never sees {@code deal_value} on any card. */
	@Test
	void theDealValueIsProjectedForTheCommercialRolesAndNobodyElse() throws Exception {
		given(board.forCaller(any(), any())).willReturn(List.of(
				row("IE-2026-0001", Stage.DOC_COLLECTION, ExceptionState.NONE, null)));

		for (Role sees : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER)) {
			mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(sees)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].dealValue").value(1450.00));
		}
		for (Role blind : List.of(Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(blind)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].caseCode").value("IE-2026-0001"))
					.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].dealValue").doesNotExist());
		}
	}

	@Test
	void theBoardIsNotReachableWithoutASession() throws Exception {
		mockMvc.perform(get("/api/cases/board"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}
}
