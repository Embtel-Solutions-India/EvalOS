package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.PipelineAssignmentService;
import com.ie.evalos.service.TeamMemberAdminService;
import com.ie.evalos.service.TeamMemberQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unit 68: only the Administrator writes the staff directory (D78; it was the GM before). The rules are {@code TeamMemberAdminServiceTest}'s. */
@WebMvcTest(controllers = TeamMemberController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class TeamMemberAdminRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String CREATE = """
			{"displayName":"New CM","email":"cm2@ie.test","role":"CASE_MANAGER",
			 "brandId":"11111111-1111-1111-1111-111111111111","password":"a-long-enough-password"}""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	TeamMemberQueryService teamMembers;

	@MockitoBean
	PipelineAssignmentService pipelines;

	@MockitoBean
	TeamMemberAdminService admin;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				(role == Role.GM || role == Role.ADMIN) ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void nobodyButTheGmCreatesDeactivatesOrSetsAPassword() throws Exception {
		UUID id = UUID.randomUUID();
		for (Role role : new Role[] { Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.EXPERT_NETWORK_MANAGER }) {
			mockMvc.perform(post("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(role))
					.contentType(MediaType.APPLICATION_JSON).content(CREATE))
					.andExpect(status().isForbidden());
			mockMvc.perform(put("/api/team-members/{id}/active", id).header(HttpHeaders.AUTHORIZATION, bearer(role))
					.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
					.andExpect(status().isForbidden());
			mockMvc.perform(put("/api/team-members/{id}/password", id).header(HttpHeaders.AUTHORIZATION, bearer(role))
					.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a-long-enough-password\"}"))
					.andExpect(status().isForbidden());
		}
		verify(admin, never()).create(any(), any());
		verify(admin, never()).setActive(any(), org.mockito.ArgumentMatchers.anyBoolean());
		verify(admin, never()).setPassword(any(), any());
	}

	@Test
	void theAdminCreatesAMember() throws Exception {
		org.mockito.BDDMockito.given(admin.create(any(), any())).willAnswer(invocation -> {
			var member = org.mockito.Mockito.mock(com.ie.evalos.domain.TeamMember.class);
			org.mockito.Mockito.when(member.getRole()).thenReturn(Role.CASE_MANAGER);
			return member;
		});

		mockMvc.perform(post("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
				.contentType(MediaType.APPLICATION_JSON).content(CREATE))
				.andExpect(status().isOk());
	}
}
