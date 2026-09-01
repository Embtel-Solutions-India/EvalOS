package com.ie.evalos.web;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.CaseDetailService;
import com.ie.evalos.service.CaseLifecycleService;
import com.ie.evalos.service.PortalAccessService;
import com.ie.evalos.service.PortalCaseService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two chains, over the real filter stack — and the criterion that matters most in Unit 14:
 * <strong>neither accepts the other's credential.</strong> Two chains that do are one chain.
 *
 * <p>Both configurations are imported together on purpose. Asserting the portal chain alone would
 * prove nothing about the direction that actually leaks: a staff JWT arriving on a portal route.
 */
@WebMvcTest(controllers = { ClientPortalController.class, CaseController.class })
@Import({ PortalSecurityConfig.class, SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class ClientPortalTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID IE_CASE = UUID.randomUUID();
	private static final UUID XP_CASE = UUID.randomUUID();

	private static final String IE_TOKEN = "ie-client-token";
	private static final String XP_TOKEN = "xp-client-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	PortalAccessService portalAccess;

	@MockitoBean
	PortalCaseService portal;

	@MockitoBean
	CaseLifecycleService lifecycle;

	@MockitoBean
	RefundService refunds;

	@MockitoBean
	CaseDetailService details;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String staffBearer(Role role) {
		return "Bearer " + jwtService.issue(new StaffPrincipal(
				UUID.randomUUID(), role + "@evalos.local", "Staff", role, BRAND_IE, null, null, true));
	}

	private void givenTwoLiveLinks() {
		given(portalAccess.resolve(IE_TOKEN)).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), BRAND_IE, IE_CASE, PortalAudience.CLIENT)));
		given(portalAccess.resolve(XP_TOKEN)).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), BRAND_XP, XP_CASE, PortalAudience.CLIENT)));
		given(portal.clientView(any())).willAnswer(call -> {
			PortalPrincipal principal = call.getArgument(0);
			return view(principal.caseId() == IE_CASE ? "IE-2026-0001" : "XP-2026-0002");
		});
	}

	private static PortalCaseService.ClientDraftView view(String reference) {
		return new PortalCaseService.ClientDraftView("Anita Rao", ServiceType.EXPERT_OPINION_LETTER, reference,
				"https://docs.google.com/document/d/draft/edit", 2, ClientApprovalStatus.PENDING, true);
	}

	/**
	 * Two tokens, crossed. There is no case parameter to swap, so this is what "the same token on
	 * another case is impossible" reduces to: each token reads its own case and there is no request
	 * that could make it read the other.
	 */
	@Test
	void eachTokenReadsItsOwnCaseAndThereIsNoWayToAskForAnother() throws Exception {
		givenTwoLiveLinks();

		mockMvc.perform(get("/api/portal/client/case").header(PortalTokenFilter.HEADER, IE_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.caseReference").value("IE-2026-0001"));

		mockMvc.perform(get("/api/portal/client/case").header(PortalTokenFilter.HEADER, XP_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.caseReference").value("XP-2026-0002"));
	}

	/**
	 * The client's view carries none of the fields that belong to somebody else.
	 *
	 * <p>Asserted against the serialized response rather than the record, because what leaks is what
	 * is on the wire — and a nested DTO or an added component would show up here.
	 */
	@Test
	void theClientResponseCarriesNothingThatIsNotTheirs() throws Exception {
		givenTwoLiveLinks();

		String body = mockMvc.perform(get("/api/portal/client/case").header(PortalTokenFilter.HEADER, IE_TOKEN))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		for (String forbidden : new String[] { "dealValue", "pmStrategyNotes", "invoiceRef",
				"campaignAttribution", "driveLink", "assignedPm", "assignedCm", "assignedCoordinator",
				"expertName", "expertId", "expertTier", "checklist" }) {
			org.assertj.core.api.Assertions.assertThat(body)
					.as("the client portal must not carry %s", forbidden)
					.doesNotContain(forbidden);
		}
	}

	/** A staff JWT is not a portal credential. */
	@Test
	void aStaffTokenIsRefusedOnThePortalChain() throws Exception {
		for (Role role : Role.values()) {
			mockMvc.perform(get("/api/portal/client/case")
					.header(HttpHeaders.AUTHORIZATION, staffBearer(role)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.error.code").value("PORTAL_LINK_INVALID"));
		}
		// And the portal service was never even asked: JwtFilter is not in that chain, so there is
		// nothing for a staff token to be resolved into.
		verifyNoInteractions(portal);
	}

	/** And the other direction, which is the one that would be a real leak. */
	@Test
	void aPortalTokenIsRefusedOnTheStaffChain() throws Exception {
		givenTwoLiveLinks();

		mockMvc.perform(get("/api/cases/{id}", IE_CASE).header(PortalTokenFilter.HEADER, IE_TOKEN))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
		mockMvc.perform(post("/api/cases/{id}/draft/client-approve", IE_CASE)
				.header(PortalTokenFilter.HEADER, IE_TOKEN))
				.andExpect(status().isUnauthorized());

		// The header is not even read on this chain: the portal filter exists only inside the portal
		// chain, which is what stops it authenticating a staff route.
		verify(portalAccess, never()).resolve(any());
		verifyNoInteractions(details, lifecycle);
	}

	/**
	 * Unknown, expired and revoked are indistinguishable — the service answers empty for all three
	 * (proved in {@code PortalAccessServiceTest}), and this is the other half: the chain turns every
	 * empty into one identical 401, with the same code and the same words as a missing header.
	 */
	@Test
	void everyBadLinkFailsIdenticallyAndSaysNothingAboutWhy() throws Exception {
		given(portalAccess.resolve(any())).willReturn(Optional.empty());

		String[] bodies = new String[3];
		String[] tokens = { "never-existed", "expired-yesterday", "revoked-by-a-re-mint" };
		for (int i = 0; i < tokens.length; i++) {
			bodies[i] = mockMvc.perform(get("/api/portal/client/case")
					.header(PortalTokenFilter.HEADER, tokens[i]))
					.andExpect(status().isUnauthorized())
					.andReturn().getResponse().getContentAsString();
		}
		org.assertj.core.api.Assertions.assertThat(bodies[1]).isEqualTo(bodies[0]);
		org.assertj.core.api.Assertions.assertThat(bodies[2]).isEqualTo(bodies[0]);

		// A missing header is the same answer again, so "is that link known?" is unanswerable.
		mockMvc.perform(get("/api/portal/client/case"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("PORTAL_LINK_INVALID"));
		verifyNoInteractions(portal);
	}

	/** An expert's token is not a client's, checked in the one place the audience is checked. */
	@Test
	void anExpertTokenIsRefusedOnAClientRoute() throws Exception {
		given(portalAccess.resolve("expert-token")).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), BRAND_IE, IE_CASE, PortalAudience.EXPERT)));

		mockMvc.perform(get("/api/portal/client/case").header(PortalTokenFilter.HEADER, "expert-token"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(portal);
	}

	@Test
	void theClientApprovesAndRequestsRevisionsThroughTheirOwnRoutes() throws Exception {
		givenTwoLiveLinks();
		given(portal.approve(any())).willReturn(view("IE-2026-0001"));
		given(portal.requestRevisions(any(), any())).willReturn(view("IE-2026-0001"));

		mockMvc.perform(post("/api/portal/client/approve").header(PortalTokenFilter.HEADER, IE_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		mockMvc.perform(post("/api/portal/client/request-revisions")
				.header(PortalTokenFilter.HEADER, IE_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"notes\":\"please soften the conclusion\"}"))
				.andExpect(status().isOk());

		// Revisions with no reason are useless to the Case Manager, so the reason is required.
		mockMvc.perform(post("/api/portal/client/request-revisions")
				.header(PortalTokenFilter.HEADER, IE_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"notes\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}
}
