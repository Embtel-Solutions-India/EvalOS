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
})
class ClientApplicationRoutesTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ClientApplicationService applications;

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
}
