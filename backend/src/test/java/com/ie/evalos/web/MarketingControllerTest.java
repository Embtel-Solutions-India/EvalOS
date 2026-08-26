package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.DateRange;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.MarketingPipelineService;
import com.ie.evalos.service.MarketingPipelineService.Detail;
import com.ie.evalos.service.MarketingPipelineService.Funnel;
import com.ie.evalos.service.MarketingPipelineService.MarketingPipeline;
import com.ie.evalos.service.MarketingPipelineService.Outcome;
import com.ie.evalos.service.MarketingPipelineService.SourceRow;
import com.ie.evalos.service.MarketingPipelineService.StageFunnel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The marketing funnel's route, and the two claims about it that are scoping claims rather than
 * preferences: <strong>only the GM may read it, and no parameter can narrow it.</strong>
 *
 * <p>Modelled on {@code BrandControllerTest}, which guards the app's other cross-brand read, for
 * the same reason: when a screen cannot be brand-scoped, the role list <em>is</em> the scoping —
 * so it is the thing worth asserting rather than the payload's shape.
 */
@WebMvcTest(controllers = MarketingController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class MarketingControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final MarketingPipeline FUNNEL = new MarketingPipeline("Google ADS Pipeline", 33,
			new BigDecimal("38200"),
			List.of(new StageFunnel("new", "New Lead", 7, new BigDecimal("7000"), 21, Outcome.OPEN),
					new StageFunnel("warm", "Warm", 26, new BigDecimal("31200"), 79, Outcome.OPEN)),
			List.of(new SourceRow("Call Back Form-----ADS", 20, new BigDecimal("24000")),
					new SourceRow("Unattributed", 13, new BigDecimal("14200"))),
			Instant.parse("2026-08-25T18:09:32Z"),
			"month", LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 26), Detail.READY);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	MarketingPipelineService pipeline;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theGmGetsTheFunnelWithItsOwnAge() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/ads-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.pipelineName").value("Google ADS Pipeline"))
				.andExpect(jsonPath("$.data.totalDeals").value(33))
				.andExpect(jsonPath("$.data.stages.length()").value(2))
				.andExpect(jsonPath("$.data.stages[1].name").value("Warm"))
				.andExpect(jsonPath("$.data.stages[1].deals").value(26))
				.andExpect(jsonPath("$.data.sources[0].source").value("Call Back Form-----ADS"))
				// The payload is cached, so the screen has to be able to state how old it is. A
				// figure with no timestamp reads as live whether it is or not.
				.andExpect(jsonPath("$.data.readAt").exists())
				// The chart's dataset is `stages` — one bar per stage, with its own count.
				.andExpect(jsonPath("$.data.stages[0].deals").value(7))
				.andExpect(jsonPath("$.data.stages[1].deals").value(26));
	}

	/**
	 * The email funnel is a second route onto the same service, and the only thing that must
	 * differ is <strong>which pipeline it asks for</strong>.
	 *
	 * <p>Asserted on the {@code Funnel} the service receives rather than on the body: the body is
	 * a stub and would pass whichever funnel the controller passed, which is exactly the bug —
	 * the two payloads are the same shape, so a copy-paste that left {@code ADS} in both routes
	 * would show the ads figures under the email heading with nothing to contradict it.
	 */
	@Test
	void theEmailRouteReadsTheEmailPipelineAndNotTheAdsOne() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/email-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.readAt").exists());

		then(pipeline).should().forCaller(Funnel.EMAIL, DateRange.YEAR);
	}

	/**
	 * The email funnel reads the same GHL location, so it carries the same scoping gap and must
	 * be behind the same door. Kept as its own case rather than folded into the loop below,
	 * because a new route defaulting to open is the failure worth catching by name.
	 */
	@Test
	void theEmailRouteIsGmOnlyForTheSameReasonTheAdsOneIs() throws Exception {
		for (Role role : List.of(Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
				Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/marketing/email-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isForbidden());
		}
		mockMvc.perform(get("/api/marketing/email-pipeline")).andExpect(status().isUnauthorized());
	}

	/**
	 * <strong>The Brand Manager is the one that matters here.</strong> They are single-brand on
	 * every other screen, and this figure reads a GHL location the brands share — so there is no
	 * `brand_id` that could narrow it for them. Admitting them would be a cross-brand leak, not a
	 * courtesy, which is why this is a test and not a comment.
	 */
	@Test
	void everyOtherRoleIsForbiddenIncludingTheBrandManager() throws Exception {
		for (Role role : List.of(Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
				Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/marketing/ads-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void anUnauthenticatedCallerIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/marketing/ads-pipeline"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	/**
	 * A {@code brandId} on the query string changes nothing, because the route declares no such
	 * parameter and the service takes none.
	 *
	 * <p>Asserted through the service call rather than the response body: the claim is that the
	 * figure <em>cannot</em> be narrowed, and `forCaller()` taking no argument is what makes that
	 * structurally true. If someone later adds a `brandId` parameter here to make the screen look
	 * scoped like its neighbours, this fails — which is the point, because it would narrow nothing
	 * while implying it had.
	 */
	@Test
	void aBrandIdOnTheQueryStringNarrowsNothing() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/ads-pipeline")
				.param("brandId", BRAND_IE.toString())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());

		then(pipeline).should().forCaller(Funnel.ADS, DateRange.YEAR);
	}

	/**
	 * The period the shell's control is on reaches the service.
	 *
	 * <p>This is the whole point of the parameter, so it is asserted on the enum the service
	 * receives rather than on the response — the response is a stub and would pass either way.
	 */
	@Test
	void passesTheRequestedPeriodThroughToTheService() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/ads-pipeline")
				.param("range", "year")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());

		then(pipeline).should().forCaller(Funnel.ADS, DateRange.YEAR);
	}

	/**
	 * An unknown period is <strong>refused</strong>, not quietly defaulted.
	 *
	 * <p>Silently answering for a month when the caller asked for a quarter is a wrong number that
	 * looks right, which is the failure this codebase keeps removing. 400 rather than 502: the
	 * caller can fix it, and GHL was never asked.
	 */
	@Test
	void anUnknownPeriodIsRefusedRatherThanDefaulted() throws Exception {
		mockMvc.perform(get("/api/marketing/ads-pipeline")
				.param("range", "quarter")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(pipeline).shouldHaveNoInteractions();
	}

	/**
	 * GHL being down is a <strong>502</strong>, not a 500.
	 *
	 * <p>The distinction is what tells the reader to try again rather than to report a bug, and
	 * the code names <em>which</em> upstream failed — a `DRIVE_UNAVAILABLE` on a marketing screen
	 * would send whoever reads the log at the wrong integration.
	 */
	@Test
	void ghlBeingDownIsAnUpstreamFaultNotABug() throws Exception {
		willThrow(new GhlUnavailableException("GHL did not answer the pipeline read"))
				.given(pipeline).forCaller(any(), any());

		mockMvc.perform(get("/api/marketing/ads-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("GHL_UNAVAILABLE"));
	}
}
