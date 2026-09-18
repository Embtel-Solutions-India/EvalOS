package com.ie.evalos.web;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.service.ClientApplicationService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit 43's routes are on the portal chain and <strong>not</strong> in its {@code permitAll} list.
 *
 * <p>{@code PortalSecurityConfig} names its open routes one by one rather than using
 * {@code /auth/**}, and says why in as many words: *"a wildcard here means the next route anyone
 * adds under that prefix is open the moment it is written, silently — this list makes it arrive
 * as a 401 in that route's own test instead."* This is that test, for the routes added next. It
 * is worth having because these are the first routes since Unit 42 to live under
 * {@code /api/portal/**}, and because what is behind them is one client's questionnaire.
 */
@WebMvcTest(controllers = ClientApplicationController.class)
@Import({ PortalSecurityConfig.class, ApiErrors.class, JwtService.class })
@TestPropertySource(properties = {
		"evalos.portal.rate-limit-per-minute=200",
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.allowed-origins=https://portal.test",
})
class ClientApplicationRoutesTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ClientApplicationService applications;

	/** Unit 53's collaborator on this controller: the document routes' service. */
	@MockitoBean
	com.ie.evalos.service.ApplicationDocumentService documents;

	// Wired into the chain as PortalTokenFilter's collaborator, not called by this controller.
	@MockitoBean
	PortalAccessService portalAccess;

	@Test
	void everyApplicationRouteRefusesAnUnauthenticatedCaller() throws Exception {
		mockMvc.perform(get("/api/portal/applications"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/portal/applications")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"serviceId\":\"academic_evaluation\",\"serviceName\":\"Academic Evaluation\"}"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/portal/applications/" + java.util.UUID.randomUUID() + "/submit"))
				.andExpect(status().isUnauthorized());
	}
	/**
	 * <strong>The autosave's preflight must pass, and it did not.</strong>
	 *
	 * <p>`PUT /api/portal/applications/{id}` is the questionnaire's autosave and the only
	 * non-GET/POST route on the portal API — and `PortalSecurityConfig` allowed
	 * {@code GET, POST, OPTIONS}. The portal is a separate origin, so the PUT was preflighted,
	 * the preflight was refused, and `DefaultCorsProcessor` answers a refusal with a bare
	 * <strong>403</strong> and a plain-text body. The client cannot read an error message out of
	 * that, so it showed its fallback — "We could not save your answers." — to a client who had
	 * just finished the form and pressed Review. Nothing in the app logs was wrong; nothing was
	 * unauthorised; the method was simply not on a list.
	 *
	 * <p>Asserted per method rather than by reading the config back, because the preflight is the
	 * thing that was broken and a browser is the only other place it shows.
	 */
	@Test
	void theAutosavePreflightIsAllowedForEveryMethodTheChainServes() throws Exception {
		for (String method : new String[] { "GET", "POST", "PUT", "DELETE" }) {
			mockMvc.perform(options("/api/portal/applications/" + java.util.UUID.randomUUID())
					.header("Origin", "https://portal.test")
					.header("Access-Control-Request-Method", method)
					.header("Access-Control-Request-Headers", "Content-Type, X-Portal-Token"))
					.andExpect(status().isOk());
		}
	}

	/**
	 * And the check can fail: a verb nothing under {@code /api/portal/**} serves is still refused.
	 *
	 * <p>Without this the test above would pass against {@code setAllowedMethods(List.of("*"))},
	 * which is the fix nobody should reach for — the list is enumerated so that adding a route
	 * with a new verb fails here instead of in a client's browser.
	 *
	 * <p><strong>This used to name DELETE, and Unit 53 is why it names PATCH instead.</strong> The
	 * document routes gave the chain a real DELETE, so this assertion started failing — correctly,
	 * and one commit before a client would have found it. PATCH reaches nothing under
	 * {@code /api/portal/**}; if that ever changes, this line is the one that says so.
	 */
	@Test
	void aMethodTheChainDoesNotServeIsStillRefusedAtThePreflight() throws Exception {
		mockMvc.perform(options("/api/portal/applications/" + java.util.UUID.randomUUID())
				.header("Origin", "https://portal.test")
				.header("Access-Control-Request-Method", "PATCH"))
				.andExpect(status().isForbidden());
	}

}
