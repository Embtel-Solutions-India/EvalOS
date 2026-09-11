package com.ie.evalos.web;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
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
 * Proves {@code permitAll} does not bypass {@link PortalTokenFilter} (Unit 42).
 *
 * <p><strong>Not an assumption.</strong> {@code permitAll} only changes what the chain's
 * {@code AuthorizationFilter} decides at the end of the chain; {@link PortalTokenFilter} is wired in
 * with {@code addFilterBefore} in {@link PortalSecurityConfig} and runs for every request the chain's
 * {@code securityMatcher} accepts, authorization rule or not. This test spends the same one-request
 * budget the filter enforces for every other route on the chain, against an auth route, to show it
 * for real rather than reason about it.
 *
 * <p>Its own file, and its own tiny per-minute budget: sharing a class (and so a cached Spring
 * context, and so the filter's one shared hit counter) with {@link ClientAuthRoutesTest}'s happier
 * requests would make this test's pass or fail depend on unrelated test order.
 */
@WebMvcTest(controllers = ClientAuthController.class)
@Import({ PortalSecurityConfig.class, ApiErrors.class, JwtService.class })
@TestPropertySource(properties = {
		"evalos.portal.rate-limit-per-minute=1",
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
})
class ClientAuthRateLimitTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ClientAccountService accounts;

	@MockitoBean
	PortalAccessService portalAccess;

	@Test
	void aSecondRequestInTheSameWindowIsThrottledEvenThoughTheRouteIsPermitAll() throws Exception {
		given(accounts.identify("ana@example.com")).willReturn(ClientAccountService.IdentifyState.UNKNOWN);
		String body = "{\"email\":\"ana@example.com\"}";

		mockMvc.perform(post("/api/portal/auth/identify")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk());

		// The route itself never refuses this — accounts.identify(..) is stubbed to answer every
		// time. Only the filter, running ahead of the permitAll decision, can produce this.
		mockMvc.perform(post("/api/portal/auth/identify")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isTooManyRequests());
	}
}
