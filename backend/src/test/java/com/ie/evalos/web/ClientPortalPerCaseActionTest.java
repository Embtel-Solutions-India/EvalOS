package com.ie.evalos.web;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.AmbiguousCaseException;
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
import com.ie.evalos.service.PortalMeetingService;
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
 * The two writes a party-scoped client could not reach until 34b.
 *
 * <p><strong>Unit 35 gave party scoping to the reads and not to the writes.</strong> D1 added
 * {@code GET /cases/{caseId}} so a client with several cases could open any of their drafts —
 * and left {@code /approve} and {@code /request-revisions} resolving the case from the token
 * alone, so both answered 409 {@code SAY_WHICH_CASE} with nowhere to say which. A client with
 * two cases could read either draft and approve neither.
 *
 * <p>{@link #aPartyClientWithTwoCasesCanNowApproveTheOneTheyMean} is the test that closes it;
 * {@link #theTokenlessRouteStillRefusesToGuess} is the one that keeps the old behaviour honest.
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

	// The meetings route (2026-09-11) gave ClientPortalController another collaborator, so this
	// slice needs it even though nothing here exercises meetings — ClientPortalMeetingTest does.
	@MockitoBean
	PortalMeetingService portalMeetings;

	@MockitoBean
	PortalInvoiceService portalInvoices;

	private void givenAPartyLink() {
		given(portalAccess.resolve(PARTY_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, null, PortalAudience.CLIENT, null, "contact_ada")));
	}

	private static PortalCaseService.ClientDraftView approved() {
		return new PortalCaseService.ClientDraftView("Ada Lovelace", ServiceType.EXPERT_OPINION_LETTER,
				"IE-2026-0001", "https://docs.example.test/draft", 2, ClientApprovalStatus.APPROVED, false);
	}

	/** The gap, closed: the client says which case, and it is approved. */
	@Test
	void aPartyClientWithTwoCasesCanNowApproveTheOneTheyMean() throws Exception {
		givenAPartyLink();
		given(portal.approve(any(), eq(FIRST_CASE))).willReturn(approved());

		mockMvc.perform(post("/api/portal/client/cases/{id}/approve", FIRST_CASE)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.approvalStatus").value("APPROVED"));

		then(portal).should().approve(any(), eq(FIRST_CASE));
	}

	@Test
	void aPartyClientCanRequestRevisionsOnTheCaseTheyMean() throws Exception {
		givenAPartyLink();
		given(portal.requestRevisions(any(), eq(FIRST_CASE), any())).willReturn(approved());

		mockMvc.perform(post("/api/portal/client/cases/{id}/request-revisions", FIRST_CASE)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"notes\":\"The dates on page two are wrong\"}"))
				.andExpect(status().isOk());

		then(portal).should().requestRevisions(any(), eq(FIRST_CASE), eq("The dates on page two are wrong"));
	}

	/**
	 * <strong>The old route still refuses to guess, and that is deliberate.</strong>
	 *
	 * <p>Approving is what sends a letter to an expert to sign, and there is no undo that reaches
	 * the client. A route that picked the newest case rather than refusing would be guessing
	 * about an irreversible act — so the ambiguity answer stays, and the new route is how a
	 * client resolves it rather than a replacement for the refusal.
	 */
	@Test
	void theTokenlessRouteStillRefusesToGuess() throws Exception {
		givenAPartyLink();
		willThrow(new AmbiguousCaseException("This link covers more than one case"))
				.given(portal).approve(any());

		mockMvc.perform(post("/api/portal/client/approve").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SAY_WHICH_CASE"));
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
				.given(portal).approve(any(), eq(THEIR_CASE));

		mockMvc.perform(post("/api/portal/client/cases/{id}/approve", THEIR_CASE)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isForbidden());
	}

	@Test
	void noTokenIsRefusedWithoutReachingTheService() throws Exception {
		mockMvc.perform(post("/api/portal/client/cases/{id}/approve", FIRST_CASE))
				.andExpect(status().isUnauthorized());

		then(portal).should(never()).approve(any(), any());
	}

	/** Revisions still need the client's words: an empty reason is useless to the Case Manager. */
	@Test
	void revisionsStillNeedAReason() throws Exception {
		givenAPartyLink();

		mockMvc.perform(post("/api/portal/client/cases/{id}/request-revisions", FIRST_CASE)
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"   \"}"))
				.andExpect(status().isBadRequest());

		then(portal).should(never()).requestRevisions(any(), any(), any());
	}
}
