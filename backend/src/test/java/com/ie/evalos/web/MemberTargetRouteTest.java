package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.DateWindow;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.GmOverviewService;
import com.ie.evalos.service.MemberTargetService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may set and read a member's monthly target (D75). */
@WebMvcTest(controllers = MemberTargetController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class MemberTargetRouteTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

	private final UUID salesId = UUID.randomUUID();
	private final UUID otherSalesId = UUID.randomUUID();

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;

	@Autowired
	MemberTargetController controller;

	@MockitoBean
	MemberTargetService targets;
	@MockitoBean
	GmOverviewService overview;
	@MockitoBean
	SellingBrand sellingBrand;
	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@BeforeEach
	void anOverviewWithTwoSalesDesks() {
		controller.clearCache();
		given(sellingBrand.id()).willReturn(BRAND_IE);
		given(overview.forCaller(any(), any())).willReturn(new GmOverviewService.GmOverview(null, List.of(),
				List.of(), null, List.of(), null, null, null, Instant.parse("2026-10-08T00:00:00Z"), null));
		given(targets.overview(eq(BRAND_IE), eq(OCT), any())).willReturn(List.of(
				row(salesId, "Sam", "1000", "900"), row(otherSalesId, "Oz", "500", "10")));
		given(targets.latestForMonth(BRAND_IE, OCT)).willReturn(java.util.Map.of(salesId, new BigDecimal("1000")));
	}

	private static MemberTargetService.TargetRow row(UUID id, String name, String target, String progress) {
		return new MemberTargetService.TargetRow(id, name, Role.SALES, MemberTargetService.TargetKind.WON_VALUE,
				new BigDecimal(target), new BigDecimal(progress));
	}

	private String bearer(Role role, UUID memberId) {
		StaffPrincipal principal = new StaffPrincipal(memberId, role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theGmListsTheMonthsTargetsWithoutAskingGhlAnything() throws Exception {
		mockMvc.perform(get("/api/gm/targets").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].memberId").value(salesId.toString()))
				.andExpect(jsonPath("$.data[0].target").value(1000));

		// The dashboard already holds each desk's progress from the overview it loaded; asking again
		// would double the heaviest GHL read on the screen.
		then(overview).should(never()).forCaller(any(), any());
	}

	@Test
	void aMemberViewAsksForTheMonthAsAWholeAndBorrowsTheDeskProgress() throws Exception {
		mockMvc.perform(get("/api/me/target").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, salesId)))
				.andExpect(status().isOk());

		ArgumentCaptor<DateWindow> window = ArgumentCaptor.forClass(DateWindow.class);
		then(overview).should().forCaller(window.capture(), any());
		assertThat(window.getValue().from()).isEqualTo(OCT);
		assertThat(window.getValue().to()).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "GM")
	void nobodyButTheGmListsTargets(Role role) throws Exception {
		mockMvc.perform(get("/api/gm/targets").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(role, UUID.randomUUID())))
				.andExpect(status().isForbidden());

		then(targets).should(never()).latestForMonth(any(), any());
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void onlyTheGmSetsATarget(Role role) throws Exception {
		mockMvc.perform(put("/api/gm/targets/" + salesId)
				.header(HttpHeaders.AUTHORIZATION, bearer(role, UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"month\":\"2026-10-15\",\"amount\":1500}"))
				.andExpect(role == Role.GM ? status().isOk() : status().isForbidden());

		then(targets).should(role == Role.GM ? times(1) : never()).set(any(), any(), any(), any());
	}

	/** The app's validation status is 400 (`InvalidRequestException`), not 422: a refusal is not stored. */
	@Test
	void aTargetForAMemberWhoCannotHaveOneIsRefusedAs400() throws Exception {
		org.mockito.BDDMockito.willThrow(new com.ie.evalos.common.InvalidRequestException(
				"Only Sales and Marketing members have a monthly target")).given(targets).set(any(), any(), any(), any());

		mockMvc.perform(put("/api/gm/targets/" + salesId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"month\":\"2026-10-15\",\"amount\":5}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("Only Sales and Marketing members have a monthly target"));
	}

	@Test
	void aNegativeTargetIsRefusedAs400BeforeTheServiceIsAsked() throws Exception {
		mockMvc.perform(put("/api/gm/targets/" + salesId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"month\":\"2026-10-15\",\"amount\":-1}"))
				.andExpect(status().isBadRequest());

		then(targets).should(never()).set(any(), any(), any(), any());
	}

	@Test
	void aMoreThanTwoDecimalOrAbsurdlyLargeAmountIsRefusedNotRoundedOrA500() throws Exception {
		for (String amount : List.of("12.345", "12345678901")) {
			mockMvc.perform(put("/api/gm/targets/" + salesId)
					.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID()))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"month\":\"2026-10-15\",\"amount\":" + amount + "}"))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(put("/api/gm/targets/" + salesId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"month\":\"2026-10-15\",\"amount\":1500.50}"))
				.andExpect(status().isOk());
	}

	@Test
	void aMonthWithJunkAfterTheMonthIsRefused() throws Exception {
		mockMvc.perform(get("/api/gm/targets").param("month", "2026-10zzz")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID())))
				.andExpect(status().isBadRequest());
	}

	/**
	 * With no month, the member's own view means "this month" by the business calendar, the same one the GM's
	 * dashboard counts in, not the browser's: a browser in India is a day ahead of it for half of every 1st.
	 */
	@Test
	void aMemberViewWithNoMonthMeansTheBusinessCalendarsCurrentMonth() throws Exception {
		LocalDate thisMonth = LocalDate.now(com.ie.evalos.service.BusinessCalendar.clock()).withDayOfMonth(1);
		given(targets.overview(eq(BRAND_IE), eq(thisMonth), any())).willReturn(List.of(row(salesId, "Sam", "1000", "900")));

		mockMvc.perform(get("/api/me/target").header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, salesId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("Sam"));

		ArgumentCaptor<DateWindow> window = ArgumentCaptor.forClass(DateWindow.class);
		then(overview).should().forCaller(window.capture(), any());
		assertThat(window.getValue().from()).isEqualTo(thisMonth);
	}

	@Test
	void aMonthThatIsNotAMonthIsABadRequestNotAServerError() throws Exception {
		mockMvc.perform(get("/api/gm/targets").param("month", "2026-13")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM, UUID.randomUUID())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aSalesMemberReadsOnlyTheirOwnRowWhateverIdTheyPass() throws Exception {
		mockMvc.perform(get("/api/me/target").param("month", "2026-10").param("memberId", otherSalesId.toString())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, salesId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("Sam"))
				.andExpect(jsonPath("$.data.target").value(1000));
	}

	/**
	 * Every Sales and Marketing board mounts this, and every tab focus re-reads it. Each uncached call is
	 * a full GM overview against GHL (two reads per pipeline), and a 429 pauses the shared pacer for the
	 * whole location, so a minute of members must cost one overview, not one each.
	 */
	@Test
	void membersLoadingTheirBoardsWithinAMinuteShareOneOverviewRead() throws Exception {
		for (UUID member : List.of(salesId, otherSalesId, salesId)) {
			mockMvc.perform(get("/api/me/target").param("month", "2026-10")
					.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, member)))
					.andExpect(status().isOk());
		}

		then(overview).should(times(1)).forCaller(any(), any());
	}

	@Test
	void aFailedOverviewReadIsNotRememberedSoTheNextLoadTriesAgain() throws Exception {
		given(overview.forCaller(any(), any())).willThrow(new IllegalStateException("GHL down"))
				.willReturn(new GmOverviewService.GmOverview(null, List.of(), List.of(), null, List.of(), null, null, null,
						Instant.parse("2026-10-08T00:00:00Z"), null));

		mockMvc.perform(get("/api/me/target").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, salesId)));
		mockMvc.perform(get("/api/me/target").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, salesId)))
				.andExpect(status().isOk());
	}

	@Test
	void aSalesMemberOnNoDeskGetsNotFound() throws Exception {
		mockMvc.perform(get("/api/me/target").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES, UUID.randomUUID())))
				.andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = { "SALES", "MARKETING" })
	void everyOtherRoleIsRefusedTheMemberView(Role role) throws Exception {
		mockMvc.perform(get("/api/me/target").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, bearer(role, salesId)))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/gm/targets").param("month", "2026-10")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/me/target").param("month", "2026-10")).andExpect(status().isUnauthorized());
	}
}
