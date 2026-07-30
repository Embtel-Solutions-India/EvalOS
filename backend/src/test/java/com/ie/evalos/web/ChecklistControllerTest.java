package com.ie.evalos.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.ChecklistService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit 10's endpoint half: who runs document collection, and what the two payloads carry.
 *
 * <p>The gate is checked as a table for the reason {@code CaseControllerTest} gives — the
 * thing worth catching is a route wired to the wrong roles, which is a comparison between
 * two lists rather than five separate behaviours.
 */
@WebMvcTest(controllers = ChecklistController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class ChecklistControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TEAM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID ITEM_ID = UUID.randomUUID();

	/**
	 * The three roles that run document collection.
	 *
	 * <p>The Coordinator owns the stage; the GM and Brand Manager are oversight, and were
	 * added deliberately — the GM is a superuser on every backend transition, so a screen
	 * they cannot open is an inconsistency rather than a safeguard.
	 */
	private static final List<Role> COORDINATION = List.of(
			Role.GM, Role.BRAND_MANAGER, Role.PROJECT_COORDINATOR);

	/** Everyone else. The PM is here even though they may call {@code docs-complete}. */
	private static final List<Role> REFUSED = List.of(
			Role.PROJECT_MANAGER, Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	ChecklistService checklists;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, TEAM, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@BeforeEach
	void aCaseWithFourDocumentsThreeOfThemIn() {
		Case subject = new Case(BRAND_IE, "IE-2026-0001", Stage.DOC_COLLECTION);
		subject.setDriveLink("https://drive.example/abc");
		subject.setStageEnteredAt(Instant.now().minus(30, ChronoUnit.HOURS));

		List<DocumentChecklistItem> items = List.of(
				new DocumentChecklistItem(BRAND_IE, CASE_ID, "Passport", ChecklistItemStatus.APPROVED),
				new DocumentChecklistItem(BRAND_IE, CASE_ID, "Degree certificate", ChecklistItemStatus.UPLOADED),
				new DocumentChecklistItem(BRAND_IE, CASE_ID, "Transcripts", ChecklistItemStatus.APPROVED),
				new DocumentChecklistItem(BRAND_IE, CASE_ID, "Translation", ChecklistItemStatus.MISSING));

		Instant chasedYesterday = Instant.now().minus(1, ChronoUnit.DAYS);
		given(checklists.forCase(any()))
				.willReturn(new ChecklistService.CaseChecklist(subject, items, chasedYesterday));
		given(checklists.board(any())).willReturn(List.of(
				new ChecklistService.BoardRow(subject, "Anita Rao", 4, 3, chasedYesterday)));
	}

	/** The five routes and the verb + body each needs, so the gate can be walked as data. */
	private static List<MockHttpServletRequestBuilder> gatedRoutes() {
		return List.of(
				get("/api/checklists/board"),
				patch("/api/cases/{id}/checklist/{itemId}", CASE_ID, ITEM_ID)
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UPLOADED\"}"),
				post("/api/cases/{id}/checklist/items", CASE_ID)
						.contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Marriage certificate\"}"),
				post("/api/cases/{id}/chase", CASE_ID));
	}

	@Test
	void documentCollectionIsRunByTheCoordinatorAndTheTwoOversightRoles() throws Exception {
		for (Role allowed : COORDINATION) {
			for (MockHttpServletRequestBuilder route : gatedRoutes()) {
				mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, bearer(allowed)))
						.andExpect(status().isOk());
			}
		}
		for (Role refused : REFUSED) {
			for (MockHttpServletRequestBuilder route : gatedRoutes()) {
				mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, bearer(refused)))
						.andExpect(status().isForbidden());
			}
		}
	}

	/**
	 * The per-case read has no role gate on purpose: every role that can open a case can see
	 * what it is waiting for, and the scoped load decides which cases those are. A gate here
	 * would refuse the PM whose case it is.
	 */
	@Test
	void everyRoleCanReadTheChecklistOfACaseTheyCanOpen() throws Exception {
		for (Role role : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
				Role.PROJECT_COORDINATOR, Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/cases/{id}/checklist", CASE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.total").value(4));
		}
	}

	@Test
	void theChecklistPayloadCarriesTheItemsTheCountsAndTheDriveLink() throws Exception {
		mockMvc.perform(get("/api/cases/{id}/checklist", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.driveLink").value("https://drive.example/abc"))
				.andExpect(jsonPath("$.data.items.length()").value(4))
				.andExpect(jsonPath("$.data.items[0].label").value("Passport"))
				.andExpect(jsonPath("$.data.items[0].status").value("APPROVED"))
				.andExpect(jsonPath("$.data.total").value(4))
				.andExpect(jsonPath("$.data.complete").value(3))
				// One item is MISSING, so the documents are not all in.
				.andExpect(jsonPath("$.data.checklistSatisfied").value(false))
				// The trail's chase timestamp, not the browser's clock: the panel showed its own
				// until this field existed, and the board reads it to retire the row from the
				// pending-docs queue.
				.andExpect(jsonPath("$.data.lastChasedAt").exists());
	}

	@Test
	void theBoardCardCarriesCompletenessAgingAndTheLastChase() throws Exception {
		mockMvc.perform(get("/api/checklists/board")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].caseCode").value("IE-2026-0001"))
				.andExpect(jsonPath("$.data[0].clientName").value("Anita Rao"))
				.andExpect(jsonPath("$.data[0].total").value(4))
				.andExpect(jsonPath("$.data[0].complete").value(3))
				.andExpect(jsonPath("$.data[0].checklistSatisfied").value(false))
				// The client derives the aging bands from this, so it has to be sent.
				.andExpect(jsonPath("$.data[0].stageEnteredAt").exists())
				.andExpect(jsonPath("$.data[0].lastChasedAt").exists());
	}

	/**
	 * Invariant 3: this screen is about documents, so the commercial figure is not on it —
	 * not hidden by the client, absent from the payload.
	 */
	@Test
	void theBoardNeverCarriesTheDealValue() throws Exception {
		mockMvc.perform(get("/api/checklists/board")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].dealValue").doesNotExist());
	}

	/** Acceptance criterion 5: another brand's checklist is not reachable, by id or otherwise. */
	@Test
	void anotherBrandsChecklistIsNotReachable() throws Exception {
		willThrow(new ForbiddenException("No case in this caller's scope")).given(checklists).forCase(any());

		mockMvc.perform(get("/api/cases/{id}/checklist", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void anItemNeedsARealStatusAndAnAddedDocumentNeedsALabel() throws Exception {
		mockMvc.perform(patch("/api/cases/{id}/checklist/{itemId}", CASE_ID, ITEM_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		// Whitespace is not a document name, and would render as a blank row forever.
		mockMvc.perform(post("/api/cases/{id}/checklist/items", CASE_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR))
				.contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anUnauthenticatedCallerReachesNothing() throws Exception {
		mockMvc.perform(get("/api/checklists/board"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}
}
