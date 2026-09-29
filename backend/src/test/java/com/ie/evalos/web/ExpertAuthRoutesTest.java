package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.service.ExpertAccountService;
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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The expert door's four routes over the real portal chain, with no portal token (Unit 59). */
@WebMvcTest(controllers = ExpertAuthController.class)
@Import({ PortalSecurityConfig.class, ApiErrors.class, JwtService.class })
@TestPropertySource(properties = {
		"evalos.portal.rate-limit-per-minute=200",
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
})
class ExpertAuthRoutesTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ExpertAccountService accounts;

	@MockitoBean
	PortalAccessService portalAccess;

	@Test
	void allFourRoutesAreReachableWithNoPortalToken() throws Exception {
		given(accounts.signIn("ada@example.com", "Correct!1"))
				.willReturn(new PortalAccessService.MintedToken("abc", Instant.now()));
		given(accounts.setPassword("a-token", "Correct!1"))
				.willReturn(new PortalAccessService.MintedToken("def", Instant.now()));

		for (String route : new String[] { "sign-up", "forgot-password" }) {
			// 204 with no body, whoever the email belongs to: the service decides, the answer never varies.
			mockMvc.perform(post("/api/portal/auth/expert/" + route).contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"ada@example.com\"}"))
					.andExpect(status().isNoContent())
					.andExpect(content().string(""));
		}
		verify(accounts, org.mockito.Mockito.times(2)).sendLink("ada@example.com");

		mockMvc.perform(post("/api/portal/auth/expert/sign-in").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ada@example.com\",\"password\":\"Correct!1\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/portal/auth/expert/set-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"a-token\",\"password\":\"Correct!1\"}"))
				.andExpect(status().isOk());
	}
}
