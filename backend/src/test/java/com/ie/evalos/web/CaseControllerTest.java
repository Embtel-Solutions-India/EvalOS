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
import com.ie.evalos.service.CaseDetailService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
	/**
	 * @param gmMayAct whether {@code GM_OR} prefixes this route's gate. True for almost all of
	 *                 them; false for the two draft-review rulings, where the GM is deliberately
	 *                 <em>excluded</em> (Unit 23a) rather than added. The two refund rulings are
	 *                 the other direction — GM-only — and are expressed by naming the GM as the
	 *                 actor with a non-GM outsider.
	 */
	private record Route(String path, Role actor, Role outsider, String body, boolean gmMayAct) {

		Route(String path, Role actor, Role outsider, String body) {
			this(path, actor, outsider, body, true);
		}
	}

	private static final List<Route> ROUTES = List.of(
			// The outsider was PROJECT_MANAGER until Unit 23 put them on this gate — a PM now claims
			// a pooled case out of their own inbox, so the Case Manager is the nearest role that
			// still may not. `aProjectManagerMayClaimAPooledCase` covers the added actor, because
			// this table holds one actor per row and assign-pm now has two.
			new Route("/assign-pm", Role.BRAND_MANAGER, Role.CASE_MANAGER,
					"{\"pmId\":\"%s\"}".formatted(SOME_ID)),
			new Route("/assign-cm", Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
					"{\"cmId\":\"%s\",\"expertId\":\"%s\"}".formatted(SOME_ID, SOME_ID)),
			new Route("/assign-coordinator", Role.PROJECT_MANAGER, Role.CASE_MANAGER,
					"{\"coordinatorId\":\"%s\"}".formatted(SOME_ID)),
			new Route("/docs-complete", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			// The body is optional (a revision filed in the same place needs no new link), so this
			// row deliberately sends none — which is also the shape every caller before Unit 14 sent.
			new Route("/draft/submit", Role.CASE_MANAGER, Role.PROJECT_MANAGER, null),
			// GM-excluded, not GM-also: reviewing a Case Manager's draft belongs to the PM who
			// assigned it, and a superuser path around the reviewer makes "who approved this"
			// ambiguous on the artefact the client pays for.
			new Route("/draft/pm-approve", Role.PROJECT_MANAGER, Role.CASE_MANAGER, null, false),
			new Route("/draft/pm-return", Role.PROJECT_MANAGER, Role.CASE_MANAGER, REASON, false),
			new Route("/draft/send-to-client", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/draft/client-approve", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, null),
			new Route("/draft/client-revisions", Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, REASON),
			// **The Case Manager is the refused role on none of these as of Unit 31.** They own the
			// signing stage — they send the letter, they get the overdue alert, and they reassign
			// — so the three expert transitions admit them and the Coordinator is the exclusion
			// that proves the gate is still a gate. A widening: nobody lost a capability.
			new Route("/expert/signed", Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, null),
			new Route("/expert/declined", Role.EXPERT_NETWORK_MANAGER, Role.PROJECT_COORDINATOR, REASON),
			new Route("/expert/timed-out", Role.CASE_MANAGER, Role.PROJECT_COORDINATOR, null),
			new Route("/reassign-expert", Role.CASE_MANAGER, Role.PROJECT_COORDINATOR,
					"{\"expertId\":\"%s\"}".formatted(SOME_ID)),
			// The CM sends the client-approved letter to the expert, which is what starts the
			// signing clock (Unit 31).
			new Route("/send-to-expert", Role.CASE_MANAGER, Role.PROJECT_COORDINATOR, null),
			new Route("/qc-approve", Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, null),
			// Both rulings on the signed letter belong to the same role: a PM who may pass it must
			// be the one who can fail it, or the failure is arranged by asking somebody else and
			// the trail loses who judged it.
			new Route("/qc-fail", Role.PROJECT_MANAGER, Role.CASE_MANAGER, REASON),
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
	CaseDetailService details;

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
		given(lifecycle.assignPm(any(), any())).willReturn(result);
		given(lifecycle.assignCaseManager(any(), any(), any(), any())).willReturn(result);
		given(lifecycle.assignCoordinator(any(), any())).willReturn(result);
		given(lifecycle.markDocsComplete(any())).willReturn(result);
		given(lifecycle.submitDraft(any(), any())).willReturn(result);
		given(lifecycle.pmApproveDraft(any(), any())).willReturn(result);
		given(lifecycle.pmReturnDraft(any(), any())).willReturn(result);
		given(lifecycle.sendDraftToClient(any())).willReturn(result);
		given(lifecycle.clientApproveDraft(any())).willReturn(result);
		given(lifecycle.clientRequestRevisions(any(), any())).willReturn(result);
		given(lifecycle.expertSigned(any())).willReturn(result);
		given(lifecycle.expertDeclined(any(), any())).willReturn(result);
		given(lifecycle.reassignExpert(any(), any(), any())).willReturn(result);
		given(lifecycle.pmQcApprove(any())).willReturn(result);
		given(lifecycle.pmQcFail(any(), any())).willReturn(result);
		given(lifecycle.sendToExpert(any())).willReturn(result);
		given(lifecycle.expertTimedOut(any())).willReturn(result);
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
			// The GM is a superuser on every transition but the two draft-review rulings, which
			// refuse them outright. Asserting the 403 rather than skipping the row is the point:
			// if `GM_OR` is ever put back, this fails.
			perform(route, Role.GM, route.gmMayAct() ? 200 : 403);
			if (route.outsider() != null) {
				perform(route, route.outsider(), 403);
			}
		}
	}

	/**
	 * The second actor on {@code assign-pm} (Unit 23): the PM takes the case themselves.
	 *
	 * <p>Its own test rather than a second column on {@link Route}, because one route having two
	 * declared actors is the exception and widening the record would invite every other row to
	 * grow one.
	 */
	@Test
	void aProjectManagerMayClaimAPooledCase() throws Exception {
		given(lifecycle.assignPm(any(), any())).willReturn(aCase());

		perform(new Route("/assign-pm", Role.PROJECT_MANAGER, null, "{\"pmId\":\"%s\"}".formatted(SOME_ID)),
				Role.PROJECT_MANAGER, 200);
	}

	/**
	 * Notes carry no role gate at all, and that is the assertion — not an omission to be tightened
	 * later.
	 *
	 * <p>Every staff role reaches the route; whether they may write on <em>this</em> case is the
	 * scoped load inside {@code CaseLifecycleService.addNote}, which is mocked out here. The two
	 * halves are tested where they live: the role surface here, the scope in
	 * {@code SecurityFlowTest}.
	 */
	@Test
	void everyStaffRoleReachesTheNotesRouteAndTheScopeDecidesTheRest() throws Exception {
		given(lifecycle.addNote(any(), any())).willReturn(aCase());

		for (Role role : Role.values()) {
			perform(new Route("/notes", role, null, "{\"note\":\"chased the client again\"}"), role, 200);
		}
	}

	/** Blank is refused at the edge, so an empty row never reaches the permanent trail. */
	@Test
	void aBlankNoteIsRejectedBeforeItIsWritten() throws Exception {
		perform(new Route("/notes", Role.CASE_MANAGER, null, "{\"note\":\"   \"}"), Role.CASE_MANAGER, 400);
	}

	/**
	 * Case Creation v2.0 deleted the manual payment path outright — GHL invoices, collects
	 * and only then marks the opportunity Won, so a staff "record payment" act would be a
	 * second way to state a fact GHL already owns. Asserted rather than assumed, because a
	 * route that quietly came back would put a second writer on the money path.
	 */
	@Test
	void thereIsNoRouteToRecordAPayment() throws Exception {
		mockMvc.perform(post("/api/cases/{id}/mark-paid", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"dealValue\":1450.00}"))
				.andExpect(status().isNotFound());
	}

	/**
	 * Unit 10's widening, pinned rather than trusted.
	 *
	 * <p>The route table above carries one actor per path, which was enough until the checklist
	 * screen gave the Brand Manager every other write on this stage — leaving them an enabled
	 * "Mark docs complete" button that this gate answered 403 on. The client half of the same
	 * assertion lives in {@code boardRules.test.ts}; the two lists have to agree.
	 */
	@Test
	void docsCompleteAdmitsEveryRoleThatWorksTheChecklistScreen() throws Exception {
		given(lifecycle.markDocsComplete(any())).willReturn(aCase());
		Route route = new Route("/docs-complete", Role.PROJECT_COORDINATOR, null, null);

		for (Role admitted : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_COORDINATOR,
				Role.PROJECT_MANAGER)) {
			perform(route, admitted, 200);
		}
		// Neither of these runs document collection or acts on its outcome.
		for (Role refused : List.of(Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			perform(route, refused, 403);
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
		willThrow(new ForbiddenException("No case in this caller's scope")).given(details).detail(any());

		mockMvc.perform(get("/api/cases/{id}", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.BRAND_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	private void givenDetail() {
		given(details.detail(any())).willReturn(new CaseDetailService.CaseWithContext(
				withNotes(), "Anita Rao", "Zara Okonkwo", "TIER_1",
				new CaseDetailService.ChecklistSummary(6, 4)));
	}

	private static Case withNotes() {
		Case subject = aCase();
		subject.setPmStrategyNotes("Lead with the publication record.");
		// Set so the projection test can assert its absence meaningfully: an unset field is
		// absent for every role and would prove nothing about the gate.
		subject.setDraftLink("https://drive.example/draft-1");
		return subject;
	}

	/**
	 * Spec deliverable 5: the restriction is a projection, not client-side hiding. A role that
	 * may not read the notes gets a payload with no notes in it, so there is nothing to reveal.
	 */
	@Test
	void strategyNotesAreProjectedOnlyToTheRolesThatMayReadThem() throws Exception {
		givenDetail();

		for (Role sees : List.of(Role.GM, Role.PROJECT_MANAGER, Role.CASE_MANAGER)) {
			mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(sees)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.pmStrategyNotes").value("Lead with the publication record."));
		}
		for (Role blind : List.of(Role.BRAND_MANAGER, Role.PROJECT_COORDINATOR, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(blind)))
					.andExpect(status().isOk())
					// The case itself still reads; only the field is absent.
					.andExpect(jsonPath("$.data.summary.caseCode").value("IE-2026-0001"))
					.andExpect(jsonPath("$.data.pmStrategyNotes").doesNotExist());
		}
	}

	/**
	 * The supply-side role may act on expert signing without learning who the client is or
	 * reading what was drafted — {@code architecture.md}'s axis, and {@code Tier.SUPPLY}'s own
	 * javadoc.
	 *
	 * <p>This test is the one the suite was missing: the loop above already asserted the Expert
	 * Network Manager reads case detail successfully and never asked what came back with it.
	 * Mirrors the board's projection, so the two screens cannot disagree about one case.
	 */
	@Test
	void caseContentIsWithheldFromTheSupplySideRole() throws Exception {
		givenDetail();

		for (Role sees : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
				Role.PROJECT_COORDINATOR, Role.CASE_MANAGER)) {
			mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(sees)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.maySeeCaseContent").value(true))
					.andExpect(jsonPath("$.data.clientName").value("Anita Rao"))
					.andExpect(jsonPath("$.data.draftLink").value("https://drive.example/draft-1"));
		}

		mockMvc.perform(get("/api/cases/{id}", CASE_ID)
						.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isOk())
				// The case still reads, and the expert still does: that is their work.
				.andExpect(jsonPath("$.data.summary.caseCode").value("IE-2026-0001"))
				.andExpect(jsonPath("$.data.expertName").value("Zara Okonkwo"))
				.andExpect(jsonPath("$.data.maySeeCaseContent").value(false))
				.andExpect(jsonPath("$.data.clientName").doesNotExist())
				.andExpect(jsonPath("$.data.draftLink").doesNotExist());
	}

	/**
	 * The bug the Unit 09 review found: read access must be STATED, not inferred from write
	 * access. A Case Manager reads the notes but cannot write them, so any client deriving "may I
	 * see this?" from `mayEditStrategyNotes` gets the one read-only role wrong — and it shows up
	 * on every case before the PM has written anything, because then the value is null either way.
	 */
	@Test
	void readAccessToStrategyNotesIsStatedSeparatelyFromWriteAccess() throws Exception {
		Case noNotesYet = aCase();
		given(details.detail(any())).willReturn(new CaseDetailService.CaseWithContext(
				noNotesYet, "Anita Rao", null, null, new CaseDetailService.ChecklistSummary(0, 0)));

		// The Case Manager: sees, cannot edit. Both flags must disagree, and the value is null
		// because nothing has been written — not because it was withheld.
		mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.maySeeStrategyNotes").value(true))
				.andExpect(jsonPath("$.data.mayEditStrategyNotes").value(false))
				.andExpect(jsonPath("$.data.pmStrategyNotes").doesNotExist());

		// A role that genuinely may not read gets false for both, so the client can tell the two
		// null cases apart.
		mockMvc.perform(get("/api/cases/{id}", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.maySeeStrategyNotes").value(false))
				.andExpect(jsonPath("$.data.mayEditStrategyNotes").value(false));

		// And the write-capable roles see as well as edit.
		for (Role writer : List.of(Role.GM, Role.PROJECT_MANAGER)) {
			mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(writer)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.maySeeStrategyNotes").value(true))
					.andExpect(jsonPath("$.data.mayEditStrategyNotes").value(true));
		}
	}

	@Test
	void onlyThePmAndTheGmMayWriteStrategyNotes() throws Exception {
		givenDetail();
		given(lifecycle.updateStrategyNotes(any(), any())).willReturn(withNotes());
		String body = "{\"pmStrategyNotes\":\"Lead with the publication record.\"}";

		for (Role allowed : List.of(Role.GM, Role.PROJECT_MANAGER)) {
			mockMvc.perform(patch("/api/cases/{id}/strategy-notes", CASE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer(allowed))
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.mayEditStrategyNotes").value(true));
		}
		// The Case Manager reads them and cannot write them — the whole point of the panel.
		for (Role refused : List.of(Role.CASE_MANAGER, Role.BRAND_MANAGER, Role.PROJECT_COORDINATOR)) {
			mockMvc.perform(patch("/api/cases/{id}/strategy-notes", CASE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer(refused))
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void blankStrategyNotesAreAllowedBecauseClearingThemIsAnEdit() throws Exception {
		givenDetail();
		given(lifecycle.updateStrategyNotes(any(), any())).willReturn(aCase());

		mockMvc.perform(patch("/api/cases/{id}/strategy-notes", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"pmStrategyNotes\":\"\"}"))
				.andExpect(status().isOk());

		// Absent is still a bad request: clearing is explicit, not implied by omission.
		mockMvc.perform(patch("/api/cases/{id}/strategy-notes", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theDetailPayloadCarriesTheJoinedContextThePageDraws() throws Exception {
		givenDetail();

		mockMvc.perform(get("/api/cases/{id}", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientName").value("Anita Rao"))
				.andExpect(jsonPath("$.data.expertName").value("Zara Okonkwo"))
				.andExpect(jsonPath("$.data.expertTier").value("TIER_1"))
				.andExpect(jsonPath("$.data.checklistTotal").value(6))
				.andExpect(jsonPath("$.data.checklistComplete").value(4));
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
