package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fourth screen over the unattributable GHL location, and it inherits the same door.
 *
 * <p>{@code architecture.md} invariant 1 says a fourth screen over this location inherits all of
 * the exception's terms — GM-only, no brand parameter — so the assertion worth making here is
 * the role gate, not the payload. Modelled on {@code MarketingControllerTest} for that reason.
 */
@WebMvcTest(controllers = GhlPipelineController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class GhlPipelineControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final List<GhlPipelineClient.Pipeline> PIPELINES = List.of(
			new GhlPipelineClient.Pipeline("pipe_aditya_01", "Aditya's  pipeline", List.of()),
			new GhlPipelineClient.Pipeline("pipe_shivangi_02", "Shivangi's Email Marketing", List.of()));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	GhlPipelineClient pipelines;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theGmGetsIdAndNameForEveryPipeline() throws Exception {
		given(pipelines.pipelines()).willReturn(PIPELINES);

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value("pipe_aditya_01"))
				.andExpect(jsonPath("$.data[0].name").value("Aditya's  pipeline"));
	}

	/**
	 * A picker needs a label and a value. Stages are Unit 38's decision to take with its own
	 * argument, not a field that arrives because GHL put it in the response.
	 */
	@Test
	void thePayloadCarriesNoStages() throws Exception {
		given(pipelines.pipelines()).willReturn(PIPELINES);

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].stages").doesNotExist());
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

	/** GHL unconfigured or refusing is a 502, as it is everywhere else this location is read. */
	@Test
	void ghlBeingUnavailableIsABadGateway() throws Exception {
		willThrow(new GhlUnavailableException("GHL is not configured in this environment"))
				.given(pipelines).pipelines();

		mockMvc.perform(get("/api/ghl/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isBadGateway());
	}
}
