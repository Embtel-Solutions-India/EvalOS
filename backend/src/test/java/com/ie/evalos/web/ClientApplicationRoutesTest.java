package com.ie.evalos.web;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.service.ClientApplicationService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * {@code /api/portal/**}, and because what is behind them is one client's request.
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
	 * <strong>Every verb the portal chain serves passes its preflight.</strong>
	 *
	 * <p>The portal is a separate origin, so a non-simple request is preflighted, and a refused
	 * preflight comes back as a bare <strong>403</strong> the client cannot read an error out of.
	 * That is how the questionnaire's autosave broke on 2026-09-18, when PUT was missing from the
	 * list. Asserted per method rather than by reading the config back, because the preflight is
	 * the thing that breaks and a browser is the only other place it shows.
	 */
	@Test
	void thePreflightIsAllowedForEveryMethodTheChainServes() throws Exception {
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
	 * <p>Without this the test above would pass against {@code setAllowedMethods(List.of("*"))}.
	 * PUT left with the questionnaire's autosave (Unit 55) and came back with case chat (Unit 57):
	 * editing a message and reacting to one. PATCH has never reached anything under {@code /api/portal/**}.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "PATCH" })
	void aMethodTheChainDoesNotServeIsStillRefusedAtThePreflight(String method) throws Exception {
		mockMvc.perform(options("/api/portal/applications/" + java.util.UUID.randomUUID())
				.header("Origin", "https://portal.test")
				.header("Access-Control-Request-Method", method))
				.andExpect(status().isForbidden());
	}

}
