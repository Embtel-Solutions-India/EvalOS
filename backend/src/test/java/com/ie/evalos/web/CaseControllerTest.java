package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.CaseLifecycleService;
import com.ie.evalos.service.RefundService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint half of Unit 04's acceptance criteria: every transition route is
 * reachable by the role the spec's table names, and by the GM, and by nobody else —
 * plus the status codes an out-of-scope case and an undeclared transition come back
 * with. The walk itself is asserted in {@code CaseLifecycleServiceTest}.
 *
 * <p>The route table is checked as data rather than as twenty tests, because the
 * thing worth catching is a route wired to the wrong gate — and that is a
 * comparison between two lists, not twenty separate behaviours.
 */
@WebMvcTest(controllers = CaseController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class CaseControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TEAM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final String SOME_ID = UUID.randomUUID().toString();

	private static final String REASON = "{\"reason\":\"because the client asked\"}";

	/**
	 * @param actor    the role the spec declares for this action
	 * @param outsider a role that must be refused, or null when the route is open to
	 *                 any authenticated staff member
	 */
	private record Route(String path, Role actor, Role outsider, String body) {
	}

	private static final List<Route> ROUTES = List.of(
			new Route("/mark-paid", Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
					"{\"dealValue\":1450.00,\"invoiceRef\":\"INV-0001\"}"),
			new Route("/assign-pm", Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
					"{\"pmId\":\"%s\"}".formatted(SOME_ID)),
			new Route("/assign-cm", Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
					"{\"cmId\":\"%s\",\"expertId\":\"%s\"}".formatted(SOME_ID, SOME_ID)),
			new Route("/assign-coordinator", Role.PROJECT_MANAGER, Role.CASE_MANAGER,
					"{\"coordinatorId\":\"%s\"}".formatted(SOME_ID)),
			new Route("/docs-complete", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/draft/submit", Role.CASE_MANAGER, Role.PROJECT_MANAGER, null),
			new Route("/draft/pm-approve", Role.PROJECT_MANAGER, Role.CASE_MANAGER, null),
			new Route("/draft/pm-return", Role.PROJECT_MANAGER, Role.CASE_MANAGER, REASON),
			new Route("/draft/send-to-client", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/draft/client-approve", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/draft/client-revisions", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, REASON),
			new Route("/expert/signed", Role.PROJECT_MANAGER, Role.CASE_MANAGER, null),
			new Route("/expert/declined", Role.EXPERT_NETWORK_MANAGER, Role.CASE_MANAGER, REASON),
			new Route("/reassign-expert", Role.EXPERT_NETWORK_MANAGER, Role.CASE_MANAGER,
					"{\"expertId\":\"%s\"}".formatted(SOME_ID)),
			new Route("/qc-approve", Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, null),
			new Route("/deliver", Role.PROJECT_COORDINATOR, Role.PROJECT_MANAGER, null),
			new Route("/close", Role.PROJECT_COORDINATOR, Role.PROJECT_MANAGER, null),
			new Route("/hold", Role.PROJECT_MANAGER, Role.CASE_MANAGER, REASON),
			new Route("/resume", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/refund/request", Role.CASE_MANAGER, null, REASON),
			new Route("/refund/approve", Role.GM, Role.PROJECT_MANAGER, null),
			new Route("/refund/deny", Role.GM, Role.BRAND_MANAGER, REASON));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	CaseLifecycleService lifecycle;

	@MockitoBean
	RefundService refunds;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, TEAM, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private static Case aCase() {
		return new Case(BRAND_IE, "IE-2026-0001", Stage.DOC_COLLECTION);
	}

	private void perform(Route route, Role as, int expectedStatus) throws Exception {
		var request = post("/api/cases/{id}" + route.path(), CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(as));
		if (route.body() != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(route.body());
		}
		mockMvc.perform(request)
				.andExpect(status().is(expectedStatus));
	}

	@Test
	void everyTransitionRouteAnswersItsDeclaredRoleAndNobodyElse() throws Exception {
		Case result = aCase();
		given(lifecycle.markPaid(any(), any(), any())).willReturn(result);
		given(lifecycle.assignPm(any(), any())).willReturn(result);
		given(lifecycle.assignCaseManager(any(), any(), any())).willReturn(result);
		given(lifecycle.assignCoordinator(any(), any())).willReturn(result);
		given(lifecycle.markDocsComplete(any())).willReturn(result);
		given(lifecycle.submitDraft(any())).willReturn(result);
		given(lifecycle.pmApproveDraft(any())).willReturn(result);
		given(lifecycle.pmReturnDraft(any(), any())).willReturn(result);
		given(lifecycle.sendDraftToClient(any())).willReturn(result);
		given(lifecycle.clientApproveDraft(any())).willReturn(result);
		given(lifecycle.clientRequestRevisions(any(), any())).willReturn(result);
		given(lifecycle.expertSigned(any())).willReturn(result);
		given(lifecycle.expertDeclined(any(), any())).willReturn(result);
		given(lifecycle.reassignExpert(any(), any())).willReturn(result);
		given(lifecycle.pmQcApprove(any())).willReturn(result);
		given(lifecycle.deliverToClient(any())).willReturn(result);
		given(lifecycle.confirmReceiptAndClose(any())).willReturn(result);
		given(lifecycle.putOnHold(any(), any())).willReturn(result);
		given(lifecycle.resumeFromHold(any())).willReturn(result);
		given(lifecycle.requestRefund(any(), any())).willReturn(result);
		given(refunds.approveRefund(any())).willReturn(result);
		given(refunds.denyRefund(any(), any())).willReturn(result);

		for (Route route : ROUTES) {
			// 200, not 404 or 405: the path and verb are wired, and the gate lets the
			// declared role through to a service call that came back.
			perform(route, route.actor(), 200);
			// The GM is a superuser on every transition, including the two they own.
			perform(route, Role.GM, 200);
			if (route.outsider() != null) {
				perform(route, route.outsider(), 403);
			}
		}
	}

	@Test
	void aTransitionAnswersTheStandardEnvelope() throws Exception {
		given(lifecycle.markDocsComplete(any())).willReturn(aCase());

		mockMvc.perform(post("/api/cases/{id}/docs-complete", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.caseCode").value("IE-2026-0001"))
				.andExpect(jsonPath("$.data.currentStage").value("DOC_COLLECTION"))
				.andExpect(jsonPath("$.data.exceptionState").value("NONE"));
	}

	@Test
	void anUndeclaredTransitionIsAConflict() throws Exception {
		willThrow(new IllegalTransitionException("PM_QC_APPROVE is not declared from DRAFT_GENERATION"))
				.given(lifecycle).pmQcApprove(any());

		mockMvc.perform(post("/api/cases/{id}/qc-approve", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
	}

	@Test
	void anotherBrandsCaseIsNotReachable() throws Exception {
		willThrow(new ForbiddenException("No case in this caller's scope")).given(lifecycle).read(any());

		mockMvc.perform(get("/api/cases/{id}", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.BRAND_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void anUnauthenticatedCallerReachesNothing() throws Exception {
		mockMvc.perform(get("/api/cases"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void aMalformedAssignPmBodyIsABadRequest() throws Exception {
		mockMvc.perform(post("/api/cases/{id}/assign-pm", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.BRAND_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}
}
