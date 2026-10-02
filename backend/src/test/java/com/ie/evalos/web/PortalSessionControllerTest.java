package com.ie.evalos.web;

import java.util.Optional;
import java.util.UUID;
import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.service.PortalAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign out (Unit 75, D72): only a live token can end itself, and it does. */
@WebMvcTest(controllers = PortalSessionController.class)
@Import({ PortalSecurityConfig.class, SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class PortalSessionControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PortalAccessService portalAccess;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@Test
	void aLiveTokenSignsItselfOut() throws Exception {
		given(portalAccess.resolve("live")).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), UUID.randomUUID(), null, PortalAudience.CLIENT, null)));

		mockMvc.perform(post("/api/portal/sign-out").header(PortalTokenFilter.HEADER, "live"))
				.andExpect(status().isOk());

		verify(portalAccess).revoke("live");
	}

	@Test
	void signOutIsNotPublic() throws Exception {
		mockMvc.perform(post("/api/portal/sign-out")).andExpect(status().isUnauthorized());

		verify(portalAccess, never()).revoke(any());
	}
}
