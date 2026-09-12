package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Segment;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.PipelineAssignmentService;
import com.ie.evalos.service.TeamMemberQueryService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/team-members/{id}/ghl-pipeline} — the role gate and the status codes.
 *
 * <p>The rules themselves are {@code PipelineAssignmentServiceTest}'s. What is asserted here is
 * that they reach the caller as <strong>400</strong> rather than 500, and that only the GM can
 * invoke them: this route changes what a person may see, and the pipeline namespace is global,
 * so a Brand Manager must not be able to reassign a pipeline that may belong to another desk.
 */
@WebMvcTest(controllers = TeamMemberController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class TeamMemberPipelineRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID MEMBER = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final String BODY = "{\"ghlPipelineId\":\"pipe_aditya_01\"}";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	TeamMemberQueryService teamMembers;

	@MockitoBean
	PipelineAssignmentService pipelines;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private static TeamMember assigned() {
		TeamMember member = new TeamMember() {
		};
		ReflectionTestUtils.setField(member, "id", MEMBER);
		ReflectionTestUtils.setField(member, "role", Role.SALES);
		ReflectionTestUtils.setField(member, "segment", Segment.ATTORNEY);
		ReflectionTestUtils.setField(member, "ghlPipelineId", "pipe_aditya_01");
		return member;
	}

	@Test
	void theGmAssignsAPipeline() throws Exception {
		given(pipelines.assign(eq(MEMBER), eq("pipe_aditya_01"))).willReturn(assigned());

		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ghlPipelineId").value("pipe_aditya_01"))
				.andExpect(jsonPath("$.data.segment").value("ATTORNEY"));
	}

	/** No email, no hash, no brand directory — a picker's response, not a staff record. */
	@Test
	void theResponseCarriesNoStaffContactDetail() throws Exception {
		given(pipelines.assign(any(), any())).willReturn(assigned());

		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(jsonPath("$.data.email").doesNotExist())
				.andExpect(jsonPath("$.data.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.data.displayName").doesNotExist());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isForbidden());

		then(pipelines).should(never()).assign(any(), any());
	}

	/**
	 * A null pipeline is <strong>400</strong>, not a constraint violation.
	 *
	 * <p>Caught by bean validation before the service is reached, which is why the service is
	 * asserted never to be called: the 400 must not depend on the database noticing.
	 */
	@Test
	void aNullPipelineIsFourHundred() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content("{\"ghlPipelineId\":null}"))
				.andExpect(status().isBadRequest());

		then(pipelines).should(never()).assign(any(), any());
	}

	@Test
	void aBlankPipelineIsFourHundred() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content("{\"ghlPipelineId\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Every rule the service refuses reaches the caller as 400 with its message — never a 500
	 * out of a {@code DataIntegrityViolationException}, which is an error path a UI cannot test.
	 */
	@Test
	void aRefusedAssignmentIsFourHundredNotFiveHundred() throws Exception {
		willThrow(new InvalidRequestException("Only SALES and MARKETING members own a pipeline"))
				.given(pipelines).assign(any(), any());

		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("Only SALES and MARKETING members own a pipeline"));
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/ghl-pipeline", MEMBER)
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}
}
