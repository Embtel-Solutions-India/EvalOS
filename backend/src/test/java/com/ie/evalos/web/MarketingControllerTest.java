package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.DateRange;
import com.ie.evalos.common.DateWindow;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

		assertThat(windowFor(Funnel.EMAIL).range()).isEqualTo(DateRange.MONTH);
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
	 * The sales funnel is a third route onto the same service, and the same one thing must differ.
	 *
	 * <p>Same reasoning as the email case above, and the copy-paste it guards against is now more
	 * likely rather than less: three routes whose bodies differ by one enum constant, returning
	 * payloads of identical shape. A route left reading {@code ADS} would put the paid-search
	 * numbers under the Sales heading, and nothing on the screen or in a log would say so.
	 */
	@Test
	void theSalesRouteReadsTheSalesPipelineAndNotAMarketingOne() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/sales-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.readAt").exists());

		assertThat(windowFor(Funnel.SALES).range()).isEqualTo(DateRange.MONTH);
	}

	/**
	 * The sales funnel reads the same GHL location, so it inherits the same scoping gap and the
	 * same door. Its own case for the reason the email one has its own: <strong>a route added
	 * without its gate is the failure that has to be caught by name</strong>, and this one is the
	 * likeliest to be added by someone thinking "sales is not marketing, so the marketing rules do
	 * not apply". They do — it is one {@code location-id} and it is still unattributable to a brand.
	 */
	@Test
	void theSalesRouteIsGmOnlyForTheSameReasonTheMarketingOnesAre() throws Exception {
		for (Role role : List.of(Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
				Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/marketing/sales-pipeline").header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isForbidden());
		}
		mockMvc.perform(get("/api/marketing/sales-pipeline")).andExpect(status().isUnauthorized());
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

		assertThat(windowFor(Funnel.ADS).range()).isEqualTo(DateRange.MONTH);
	}

	/**
	 * The period the shell's control is on reaches the service.
	 *
	 * <p>Asserted on the <strong>range the window carries</strong>, not on the window itself: the
	 * controller resolves the days against the real clock, so pinning exact dates here would make
	 * this test's meaning depend on what day it runs — and fail once a year at midnight. Which days
	 * each range resolves to is {@code DateWindowTest}'s subject, on a fixed clock.
	 */
	@Test
	void passesTheRequestedPeriodThroughToTheService() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/ads-pipeline")
				.param("range", "last-month")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());

		assertThat(windowFor(Funnel.ADS).range()).isEqualTo(DateRange.LAST_MONTH);
	}

	/**
	 * A custom window's dates reach the service <strong>verbatim</strong>.
	 *
	 * <p>The one case where exact dates can be asserted without depending on today, and the one
	 * where a bug would be invisible: a controller that dropped {@code from}/{@code to} and fell
	 * back to a named window would still answer 200 with a plausible-looking funnel.
	 */
	@Test
	void passesACustomWindowsDatesThroughUntouched() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		mockMvc.perform(get("/api/marketing/ads-pipeline")
				.param("range", "custom")
				.param("from", "2026-01-01")
				.param("to", "2026-03-31")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());

		DateWindow window = windowFor(Funnel.ADS);
		assertThat(window.range()).isEqualTo(DateRange.CUSTOM);
		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-01-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-03-31"));
	}

	/**
	 * The custom-window rules are enforced at the HTTP boundary as 400s, not 500s.
	 *
	 * <p>One test over the whole family rather than one each: the rules themselves are
	 * {@code DateWindowTest}'s subject, and what this asserts is that they arrive here as a
	 * client error the caller can fix. A malformed date reaching the service as a
	 * {@code DateTimeParseException} would surface as "report a bug in EvalOS" for a typo in a URL.
	 */
	@Test
	void aBadCustomWindowIsAClientErrorAndNotAServerError() throws Exception {
		given(pipeline.forCaller(any(), any())).willReturn(FUNNEL);

		for (String[] params : new String[][] {
				// custom with no dates at all
				{ "custom", null, null },
				// only one edge
				{ "custom", "2026-01-01", null },
				// backwards
				{ "custom", "2026-03-31", "2026-01-01" },
				// not a date
				{ "custom", "31/03/2026", "2026-04-01" },
				// dates on a NAMED range — refused rather than ignored, because ignoring them
				// answers a different question than the one the URL asks
				{ "month", "2026-01-01", "2026-01-31" } }) {
			var request = get("/api/marketing/ads-pipeline")
					.param("range", params[0])
					.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM));
			if (params[1] != null) {
				request = request.param("from", params[1]);
			}
			if (params[2] != null) {
				request = request.param("to", params[2]);
			}

			mockMvc.perform(request)
					.andExpect(status().isBadRequest())
					// VALIDATION_FAILED is what `ApiExceptionHandler` maps `InvalidRequestException`
					// to app-wide; asserted so this route answers like every other bad-input route
					// rather than inventing a code of its own.
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}

		// Nothing reached the service: a window that cannot be resolved must not become a GHL read.
		then(pipeline).should(never()).forCaller(any(), any());
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

	/**
	 * The window the controller handed the service, for the funnel it was asked about.
	 *
	 * <p>A captor rather than an equality assertion because a {@code DateWindow} carries resolved
	 * dates: building the expected one in the test would either duplicate the resolver or depend on
	 * the calendar. Capturing lets each test assert only the part it is actually about.
	 */
	private DateWindow windowFor(Funnel funnel) {
		ArgumentCaptor<DateWindow> captured = ArgumentCaptor.forClass(DateWindow.class);
		then(pipeline).should().forCaller(eq(funnel), captured.capture());
		return captured.getValue();
	}
}
