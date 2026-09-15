package com.ie.evalos.web;

import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
	private static final UUID PIPELINE = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static final String BODY = "{\"pipelineId\":\"44444444-4444-4444-4444-444444444444\"}";

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

	@Test
	void theGmPutsAMemberOnAPipeline() throws Exception {
		given(pipelines.grant(MEMBER, PIPELINE)).willReturn(List.of("pipe_aditya_01"));

		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ghlPipelineIds[0]").value("pipe_aditya_01"));
	}

	/**
	 * <strong>A member may hold several, which is the whole change.</strong>
	 *
	 * <p>{@code 00d} §6.7 retired the one-owner rule because the target pipeline set includes Case
	 * Delivery — a pipeline no single person owns. The route answers with the whole set so a screen
	 * never has to guess what the grant left behind.
	 */
	@Test
	void theResponseCarriesTheWholeSet() throws Exception {
		given(pipelines.grant(any(), any()))
				.willReturn(List.of("pipe_aditya_01", "pipe_case_delivery"));

		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(jsonPath("$.data.ghlPipelineIds.length()").value(2));
	}

	/** No email, no hash, no brand directory — a picker's response, not a staff record. */
	@Test
	void theResponseCarriesNoStaffContactDetail() throws Exception {
		given(pipelines.grant(any(), any())).willReturn(List.of("pipe_aditya_01"));

		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(jsonPath("$.data.email").doesNotExist())
				.andExpect(jsonPath("$.data.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.data.displayName").doesNotExist());
	}

	@Test
	void theGmTakesAMemberOffAPipeline() throws Exception {
		given(pipelines.revoke(MEMBER, PIPELINE)).willReturn(List.of());

		mockMvc.perform(delete("/api/team-members/{id}/pipelines/{pipelineId}", MEMBER, PIPELINE)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ghlPipelineIds.length()").value(0));
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isForbidden());

		mockMvc.perform(delete("/api/team-members/{id}/pipelines/{pipelineId}", MEMBER, PIPELINE)
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());

		then(pipelines).should(never()).grant(any(), any());
		then(pipelines).should(never()).revoke(any(), any());
	}

	/**
	 * A null pipeline is <strong>400</strong>, not a constraint violation.
	 *
	 * <p>Caught by bean validation before the service is reached, which is why the service is
	 * asserted never to be called: the 400 must not depend on the database noticing.
	 */
	@Test
	void aNullPipelineIsFourHundred() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content("{\"pipelineId\":null}"))
				.andExpect(status().isBadRequest());

		then(pipelines).should(never()).grant(any(), any());
	}

	/**
	 * <strong>A pipeline is now a UUID, and a GHL string is refused.</strong>
	 *
	 * <p>That is {@code 00d} C4 closed at the door. The old route took GHL's opaque id, so after the
	 * sub-account was replaced every member was scoped to a pipeline that no longer existed and "the
	 * board draws zero columns with no error". A mirror id is a foreign key: an id that names no
	 * real pipeline cannot be assigned at all.
	 */
	@Test
	void aRawGhlPipelineIdIsRefused() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content("{\"pipelineId\":\"pipe_aditya_01\"}"))
				.andExpect(status().isBadRequest());

		then(pipelines).should(never()).grant(any(), any());
	}

	/**
	 * Every rule the service refuses reaches the caller as 400 with its message — never a 500
	 * out of a {@code DataIntegrityViolationException}, which is an error path a UI cannot test.
	 */
	@Test
	void aRefusedAssignmentIsFourHundredNotFiveHundred() throws Exception {
		willThrow(new InvalidRequestException("Only SALES and MARKETING members work a pipeline"))
				.given(pipelines).grant(any(), any());

		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("Only SALES and MARKETING members work a pipeline"));
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(put("/api/team-members/{id}/pipelines", MEMBER)
				.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}
}
