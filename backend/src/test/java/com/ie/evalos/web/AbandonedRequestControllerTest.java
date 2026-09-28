package com.ie.evalos.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.AbandonedRequestService;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** D54: the GM and Sales read the abandoned-request list; nobody else does. */
@WebMvcTest(controllers = AbandonedRequestController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class AbandonedRequestControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	AbandonedRequestService abandoned;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "X", role,
				role == Role.GM ? null : UUID.randomUUID(), null, "pipe_mine", null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void onlyTheGmAndSalesMayRead(Role role) throws Exception {
		given(abandoned.forCaller()).willReturn(List.of());
		boolean allowed = role == Role.GM || role == Role.SALES;
		mockMvc.perform(get("/api/requests/abandoned").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(allowed ? status().isOk() : status().isForbidden());
	}
}
