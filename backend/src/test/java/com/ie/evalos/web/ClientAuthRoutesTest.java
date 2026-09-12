package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.service.ClientAccountService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four auth routes over the real portal chain (Unit 42) — the one design point
 * {@link ClientAuthControllerTest} cannot prove, because it never loads {@link PortalSecurityConfig}.
 *
 * <p>Every route is called with <strong>no {@code X-Portal-Token} header</strong>, which is the
 * whole test: on every other route on this chain that answers 401. Reaching the controller here is
 * what proves the one {@code permitAll} matcher is in place and, because {@code anyRequest()} still
 * has to come after it, in the right order.
 */
@WebMvcTest(controllers = ClientAuthController.class)
@Import({ PortalSecurityConfig.class, ApiErrors.class, JwtService.class })
@TestPropertySource(properties = {
		"evalos.portal.rate-limit-per-minute=200",
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
})
class ClientAuthRoutesTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ClientAccountService accounts;

	// Not called by ClientAuthController, but PortalSecurityConfig's chain wires it in as a
	// collaborator of PortalTokenFilter, so this slice needs it mocked to start.
	@MockitoBean
	PortalAccessService portalAccess;

	@Test
	void allFourRoutesAreReachableWithNoPortalToken() throws Exception {
		given(accounts.identify("ana@example.com")).willReturn(ClientAccountService.IdentifyState.UNKNOWN);
		given(accounts.signIn("ana@example.com", "Correct!1")).willReturn(
				new PortalAccessService.MintedToken("abc", Instant.now()));
		given(accounts.setPassword("a-token", "Correct!1")).willReturn(
				new PortalAccessService.MintedToken("def", Instant.now()));

		mockMvc.perform(post("/api/portal/auth/identify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ana@example.com\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/portal/auth/sign-in")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ana@example.com\",\"password\":\"Correct!1\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/portal/auth/forgot-password")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ana@example.com\"}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/portal/auth/set-password")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"a-token\",\"password\":\"Correct!1\"}"))
				.andExpect(status().isOk());
	}
}
