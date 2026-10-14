package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.SettingsAdminService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D83: Settings is the Administrator's alone. The rules and the secret-hiding are {@code SettingsAdminServiceTest}'s. */
@WebMvcTest(controllers = SettingsController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class SettingsRouteTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	SettingsAdminService settings;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM || role == Role.ADMIN ? null : UUID.randomUUID(), null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theAdministratorReadsChangesAndTestsSettings() throws Exception {
		given(settings.list()).willReturn(List.of());
		given(settings.testMail()).willReturn(new SettingsAdminService.TestResult(true, "Sent"));
		given(settings.testGhl()).willReturn(new SettingsAdminService.TestResult(false, "Not configured"));
		String admin = bearer(Role.ADMIN);

		mockMvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk());
		mockMvc.perform(put("/api/settings").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":{\"MAIL_PORT\":\"587\"}}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/settings/mail/test").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.ok").value(true));
		mockMvc.perform(post("/api/settings/ghl/test").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.ok").value(false));
	}

	@Test
	void noOtherRoleReachesSettingsAtAll() throws Exception {
		for (Role role : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.CASE_MANAGER, Role.SALES)) {
			String bearer = bearer(role);
			mockMvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
			mockMvc.perform(put("/api/settings").header(HttpHeaders.AUTHORIZATION, bearer)
					.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":{}}")).andExpect(status().isForbidden());
			mockMvc.perform(post("/api/settings/mail/test").header(HttpHeaders.AUTHORIZATION, bearer))
					.andExpect(status().isForbidden());
		}
		mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
		verifyNoInteractions(settings);
	}
}
