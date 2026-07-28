package com.ie.evalos.web;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real {@link SecurityConfig} is imported rather than left to Boot's
 * default chain, so this also asserts that health stays public.
 */
@WebMvcTest(HealthController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@Test
	void healthIsUpAndWrappedInTheEnvelope() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("UP"))
				.andExpect(jsonPath("$.data.service").value("evalos"))
				.andExpect(jsonPath("$.data.time").exists())
				.andExpect(jsonPath("$.error").doesNotExist());
	}
}
