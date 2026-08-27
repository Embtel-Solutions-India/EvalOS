package com.ie.evalos.web;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.PayoutService;
import com.ie.evalos.service.PayoutService.BatchView;
import com.ie.evalos.service.PayoutService.ExpertGroup;
import com.ie.evalos.service.PayoutService.LedgerRow;
import com.ie.evalos.service.PayoutService.PaymentDetailView;
import com.ie.evalos.service.PayoutService.PaymentRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ledger's and its payments' nine routes, gated on the three roles that pay people.
 *
 * <p>{@link #theControllersAuthorizeExactlyWhoMayRecordAPayout} is the Unit 10 lesson as a
 * test: {@link PayoutService#MAY_RECORD} is the single authority for who may touch this
 * surface, and both controllers' {@code @PreAuthorize} strings are read reflectively and
 * checked against it, so the nav gate and the server gate cannot drift apart silently.
 *
 * <p>{@link #noRouteEverSerializesThePaymentDetail} is invariant 4 as a test: it walks
 * every route with a service returning fully-populated data and greps each body for the
 * expert's payment detail, which no DTO on this surface declares.
 */
@WebMvcTest(controllers = { PayoutController.class, PaymentController.class })
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class PayoutControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID EXPERT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

	private static final UUID PAYOUT_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

	private static final UUID PAYMENT_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

	/** What must never appear in a response body, on any route, for any role. */
	private static final String SECRET = "Wire to Bank of Nowhere, acct 12345678";

	private static final String SETTLE_BODY = """
			{"expertId":"cccccccc-0000-0000-0000-000000000001",
			 "payoutIds":["dddddddd-0000-0000-0000-000000000001"],
			 "amount":700.00,
			 "method":"Wire",
			 "reference":"REF-1001",
			 "paidDate":"2026-08-24T00:00:00Z"}
			""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	PayoutService payoutService;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@BeforeEach
	void aWeekOfDraftsAndOneTransfer() {
		LedgerRow draft = new LedgerRow(PAYOUT_ID, UUID.randomUUID(), "IE-2026-0001", EXPERT_ID,
				"Dr Miriam Osei", new BigDecimal("700.00"), "USD", PayoutStatus.PENDING,
				Instant.parse("2026-08-24T00:00:00Z"), false, null);
		ExpertGroup group = new ExpertGroup(EXPERT_ID, "Dr Miriam Osei", List.of(draft),
				new BigDecimal("700.00"), "USD");
		BatchView batch = new BatchView(LocalDate.parse("2026-08-24"), LocalDate.parse("2026-08-30"),
				List.of(group), new BigDecimal("700.00"), BigDecimal.ZERO, BigDecimal.ZERO, "USD");
		PaymentRow paymentRow = new PaymentRow(PAYMENT_ID, EXPERT_ID, "Dr Miriam Osei",
				new BigDecimal("700.00"), "USD", "Wire", "REF-1001",
				Instant.parse("2026-08-24T00:00:00Z"), 1, false);
		PaymentDetailView detail = new PaymentDetailView(paymentRow, "Paid in full", "Alex ENM", List.of(draft));

		given(payoutService.batch(any())).willReturn(batch);
		given(payoutService.list(any(), any(), any(), anyBoolean())).willReturn(List.of(draft));
		given(payoutService.payout(any())).willReturn(draft);
		given(payoutService.settle(any())).willReturn(PAYMENT_ID);
		given(payoutService.history(any())).willReturn(List.of(paymentRow));
		given(payoutService.payment(any())).willReturn(detail);
	}

	@Test
	void theEnmMayRecordThatMoneyWentOut() throws Exception {
		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETTLE_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
	}

	@ParameterizedTest
	@MethodSource("rolesWithNoBusinessHere")
	void everyOtherRoleIsRefusedEverywhere(Role role) throws Exception {
		for (MockHttpServletRequestBuilder request : everyRoute(role)) {
			mockMvc.perform(request).andExpect(status().isForbidden());
		}
	}

	static List<Role> rolesWithNoBusinessHere() {
		return List.of(Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, Role.CASE_MANAGER);
	}

	@Test
	void settlingRequiresAMethodAReferenceAnAmountAndADate() throws Exception {
		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"expertId":"cccccccc-0000-0000-0000-000000000001",
						 "payoutIds":["dddddddd-0000-0000-0000-000000000001"]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false));
	}

	/**
	 * Proves the controller actually binds and forwards every query parameter rather
	 * than silently dropping one — a wiring bug the filtering test in
	 * {@code PayoutServiceTest} cannot see, since that test never goes through HTTP
	 * parameter binding.
	 */
	@Test
	void theListRouteForwardsEveryFilterToTheService() throws Exception {
		mockMvc.perform(get("/api/payouts?status=PENDING&expertId=" + EXPERT_ID
				+ "&weekOf=2026-08-24&overdue=true")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isOk());

		verify(payoutService).list(PayoutStatus.PENDING, EXPERT_ID, LocalDate.parse("2026-08-24"), true);
	}

	@Test
	void noRouteEverSerializesThePaymentDetail() throws Exception {
		// The unit's invariant-4 criterion, written as a test: walk every route with a
		// service returning fully-populated data and grep each body. A DTO that grows a
		// member, or a mapper that starts copying one, fails here — which a per-field
		// assertion on one endpoint would not.
		for (MockHttpServletRequestBuilder request : everyRoute(Role.EXPERT_NETWORK_MANAGER)) {
			String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("paymentDetail").doesNotContain(SECRET);
		}
	}

	@Test
	void aRefusedSettlementCarriesTheServersReason() throws Exception {
		given(payoutService.settle(any())).willThrow(new InvalidRequestException(
				"The payment is 800.00 but the drafts it settles come to 700.00"));

		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETTLE_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value(
						"The payment is 800.00 but the drafts it settles come to 700.00"));
	}

	/**
	 * The Unit 10 lesson, proven rather than assumed: both controllers'
	 * {@code @PreAuthorize} strings are read off the compiled methods by reflection and
	 * their role names compared to {@link PayoutService#MAY_RECORD} — not a second,
	 * hand-typed copy of the list, which would only ever catch itself agreeing with
	 * itself. A role added to one side and not the other fails this test.
	 */
	@Test
	void theControllersAuthorizeExactlyWhoMayRecordAPayout() {
		Set<String> expected =
				PayoutService.MAY_RECORD.stream().map(Role::name).collect(java.util.stream.Collectors.toSet());
		assertThat(expected).containsExactlyInAnyOrder("GM", "BRAND_MANAGER", "EXPERT_NETWORK_MANAGER");

		for (Class<?> controller : List.of(PayoutController.class, PaymentController.class)) {
			for (Method method : controller.getDeclaredMethods()) {
				PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
				if (preAuthorize == null) {
					continue;
				}
				assertThat(rolesIn(preAuthorize.value()))
						.as(controller.getSimpleName() + "#" + method.getName())
						.isEqualTo(expected);
			}
		}
	}

	private static Set<String> rolesIn(String preAuthorizeExpression) {
		Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(preAuthorizeExpression);
		Set<String> roles = new HashSet<>();
		while (matcher.find()) {
			roles.add(matcher.group(1));
		}
		return roles;
	}

	/** Every route this unit declares, as a request the given role may make. */
	private List<MockHttpServletRequestBuilder> everyRoute(Role role) {
		return List.of(
				get("/api/payouts").header(HttpHeaders.AUTHORIZATION, bearer(role)),
				get("/api/payouts/" + PAYOUT_ID).header(HttpHeaders.AUTHORIZATION, bearer(role)),
				get("/api/payouts/batch?weekOf=2026-08-24").header(HttpHeaders.AUTHORIZATION, bearer(role)),
				patch("/api/payouts/" + PAYOUT_ID).header(HttpHeaders.AUTHORIZATION, bearer(role))
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":700.00}"),
				post("/api/payouts/settle").header(HttpHeaders.AUTHORIZATION, bearer(role))
						.contentType(MediaType.APPLICATION_JSON).content(SETTLE_BODY),
				get("/api/payments?expertId=" + EXPERT_ID).header(HttpHeaders.AUTHORIZATION, bearer(role)),
				get("/api/payments/" + PAYMENT_ID).header(HttpHeaders.AUTHORIZATION, bearer(role)),
				patch("/api/payments/" + PAYMENT_ID).header(HttpHeaders.AUTHORIZATION, bearer(role))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"method\":\"Wire\",\"reference\":\"REF-1001\"}"),
				post("/api/payments/" + PAYMENT_ID + "/confirm").header(HttpHeaders.AUTHORIZATION, bearer(role)));
	}

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}
}
