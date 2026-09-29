package com.ie.evalos.web;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.service.PortalAccessService;
import com.ie.evalos.service.PortalCaseService;
import com.ie.evalos.service.PortalInvoiceService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The client's two answers to a draft, each naming the case and the version (Unit 58).
 *
 * <p>The token-scoped {@code /approve} and {@code /request-revisions}, which answered 409
 * {@code SAY_WHICH_CASE} on a party token with several cases, and 34b's version-less
 * {@code /cases/{id}/approve}, were removed in Unit 58 phase 3: naming the version is what stops
 * a stale tab approving a newer draft it never saw.
 */
@WebMvcTest(controllers = ClientPortalController.class)
@Import({ PortalSecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class ClientPortalPerCaseActionTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID FIRST_CASE = UUID.randomUUID();
	private static final UUID THEIR_CASE = UUID.randomUUID();
	private static final String PARTY_TOKEN = "party-token";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PortalAccessService portalAccess;

	@MockitoBean
	PortalCaseService portal;

	@MockitoBean
	PortalInvoiceService portalInvoices;

	private void givenAPartyLink() {
		given(portalAccess.resolve(PARTY_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, null, PortalAudience.CLIENT, null, "contact_ada")));
	}

	private static PortalCaseService.ClientDraftView approved() {
		return new PortalCaseService.ClientDraftView("Ada Lovelace", ServiceType.EXPERT_OPINION_LETTER,
				"IE-2026-0001", "https://docs.example.test/draft", 2, ClientApprovalStatus.APPROVED, false, "In progress", 2,
				java.util.List.of());
	}

	private static final UUID DRAFT = UUID.randomUUID();

	/** A client with several cases approves the version they mean. */
	@Test
	void aPartyClientApprovesTheNamedVersionOfTheNamedCase() throws Exception {
		givenAPartyLink();
		given(portal.approveDraft(any(), eq(FIRST_CASE), eq(DRAFT))).willReturn(approved());

		mockMvc.perform(post("/api/portal/client/cases/{id}/drafts/{draftId}/approve", FIRST_CASE, DRAFT)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.approvalStatus").value("APPROVED"));

		then(portal).should().approveDraft(any(), eq(FIRST_CASE), eq(DRAFT));
	}

	@Test
	void aPartyClientCanRequestChangesWithTheirWords() throws Exception {
		givenAPartyLink();
		given(portal.requestChanges(any(), eq(FIRST_CASE), eq(DRAFT), any())).willReturn(approved());

		mockMvc.perform(post("/api/portal/client/cases/{id}/drafts/{draftId}/request-changes", FIRST_CASE, DRAFT)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"notes\":\"The dates on page two are wrong\"}"))
				.andExpect(status().isOk());

		then(portal).should().requestChanges(any(), eq(FIRST_CASE), eq(DRAFT), eq("The dates on page two are wrong"));
	}

	/** The note is optional: the comment thread on the version carries the detail. */
	@Test
	void requestingChangesNeedsNoNote() throws Exception {
		givenAPartyLink();
		given(portal.requestChanges(any(), eq(FIRST_CASE), eq(DRAFT), any())).willReturn(approved());

		mockMvc.perform(post("/api/portal/client/cases/{id}/drafts/{draftId}/request-changes", FIRST_CASE, DRAFT)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk());

		then(portal).should().requestChanges(any(), eq(FIRST_CASE), eq(DRAFT), eq(null));
	}

	/**
	 * A case that is not this party's answers 403, not 404.
	 *
	 * <p>The path variable is only safe because it is checked against the credential first, and
	 * 404 would make the response an oracle for which case ids exist in the brand.
	 */
	@Test
	void anotherPartysCaseIsForbiddenNotMissing() throws Exception {
		givenAPartyLink();
		willThrow(new ForbiddenException("That case is not yours"))
				.given(portal).approveDraft(any(), eq(THEIR_CASE), any());

		mockMvc.perform(post("/api/portal/client/cases/{id}/drafts/{draftId}/approve", THEIR_CASE, DRAFT)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isForbidden());
	}

	@Test
	void noTokenIsRefusedWithoutReachingTheService() throws Exception {
		mockMvc.perform(post("/api/portal/client/cases/{id}/drafts/{draftId}/approve", FIRST_CASE, DRAFT))
				.andExpect(status().isUnauthorized());

		then(portal).should(never()).approveDraft(any(), any(), any());
	}

	/** The retired routes are gone, not quietly still answering. */
	@Test
	void theRetiredRoutesNoLongerExist() throws Exception {
		givenAPartyLink();
		for (String path : new String[] { "/api/portal/client/approve", "/api/portal/client/request-revisions",
				"/api/portal/client/cases/" + FIRST_CASE + "/approve" }) {
			mockMvc.perform(post(path).header(PortalTokenFilter.HEADER, PARTY_TOKEN))
					.andExpect(status().is4xxClientError());
		}
		then(portal).shouldHaveNoInteractions();
	}
}
