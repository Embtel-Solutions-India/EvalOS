package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.service.PipelineMirrorService;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fourth screen over the unattributable GHL location, and it inherits the same door.
 *
 * <p>{@code architecture.md} invariant 1 says a fourth screen over this location inherits all of
 * the exception's terms — GM-only, no brand parameter — so the assertion worth making here is
 * the role gate, not the payload.
 */
@WebMvcTest(controllers = GhlPipelineController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class GhlPipelineControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final List<Pipeline> PIPELINES = List.of(
			mirrored("pipe_aditya_01", "Aditya's  pipeline", 0),
			mirrored("pipe_shivangi_02", "Shivangi's Email Marketing", 1));

	private static Pipeline mirrored(String ghlId, String name, int position) {
		Pipeline pipeline = new Pipeline(BRAND_IE, ghlId, name, position);
		ReflectionTestUtils.setField(pipeline, "id", UUID.randomUUID());
		return pipeline;
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	PipelineMirrorService pipelines;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theGmGetsIdAndNameForEveryPipeline() throws Exception {
		given(pipelines.all()).willReturn(PIPELINES);

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value("pipe_aditya_01"))
				.andExpect(jsonPath("$.data[0].name").value("Aditya's  pipeline"));
	}

	/**
	 * A picker needs a label and a value, and now also how old the answer is.
	 *
	 * <p>Unit 38 decided the payload carries no stage <em>list</em> — that stays true. What Unit
	 * 44a adds is a <em>count</em> and {@code syncedAt}, because the rows come from a mirror the
	 * sweep refreshes rather than from a live GHL read: a screen that cannot say how stale its
	 * options are will present a deleted pipeline as a current choice.
	 */
	@Test
	void thePayloadCarriesAStageCountAndItsAgeButNoStageList() throws Exception {
		given(pipelines.all()).willReturn(PIPELINES);
		given(pipelines.stagesOf(org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].stages").value(0))
				.andExpect(jsonPath("$.data[0].syncedAt").exists())
				.andExpect(jsonPath("$.data[0].purpose").value("UNASSIGNED"))
				.andExpect(jsonPath("$.data[0].stages[0]").doesNotExist());
	}

	/**
	 * Every role but the GM, including the two Unit 36 adds.
	 *
	 * <p><strong>SALES and MARKETING are refused too, and that is the point of asserting over
	 * the whole enum.</strong> They are the roles the pipeline list exists to serve, which makes
	 * "let them see the list" the plausible-sounding change — and it would hand a brand-locked
	 * role the names of every other desk's funnel on a location EvalOS cannot attribute.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/ghl/pipelines")).andExpect(status().isUnauthorized());
	}

	/**
	 * <strong>An unfilled mirror is an empty list, not a 502 — and that is a behaviour change worth
	 * pinning.</strong>
	 *
	 * <p>This route used to read GHL live, so an unconfigured or refusing GHL was a bad gateway.
	 * Unit 44a moved it onto the mirror, and the mirror cannot fail that way: it is a local read
	 * that either has rows or does not. The recovery is a button rather than a deploy —
	 * {@code POST /api/jobs/PIPELINE_MIRROR/run} already exists and is GM-only, which is why no
	 * route was added here for it.
	 *
	 * <p>The screen is what must not present the empty case as "this location has no pipelines":
	 * {@code syncedAt} is on every row it does return precisely so the age is visible.
	 */
	@Test
	void anUnfilledMirrorAnswersWithAnEmptyListRatherThanAnError() throws Exception {
		given(pipelines.all()).willReturn(List.of());

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
	}

	/** The purpose is EvalOS's own column, and an unknown value is refused by name. */
	@Test
	void anUnknownPurposeIsRefusedWithTheAllowedValues() throws Exception {
		mockMvc.perform(put("/api/ghl/pipelines/" + UUID.randomUUID() + "/purpose")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"WHATEVER\"}"))
				.andExpect(status().isBadRequest());
	}

	/** Setting a purpose is a GM act: it decides where a client's request is routed. */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void onlyTheGmMaySetAPurpose(Role role) throws Exception {
		mockMvc.perform(put("/api/ghl/pipelines/" + UUID.randomUUID() + "/purpose")
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"SALES\"}"))
				.andExpect(status().isForbidden());
	}
}
