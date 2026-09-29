package com.ie.evalos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.PayoutRegisterService;
import com.ie.evalos.service.PayoutRegisterService.Filter;
import com.ie.evalos.service.PayoutRegisterService.RegisterStatus;
import com.ie.evalos.service.PayoutService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unit 65: the Payouts module's reads sit behind exactly {@link PayoutService#MAY_RECORD}, and
 * their literal paths win over {@code PayoutController}'s {@code GET /api/payouts/{id}}.
 */
@WebMvcTest(controllers = { PayoutController.class, PayoutRegisterController.class })
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class PayoutRegisterControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	PayoutService payoutService;

	@MockitoBean
	PayoutRegisterService register;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@Test
	void theGateNamesExactlyThePayoutRoles() {
		String gate = PayoutRegisterController.class.getAnnotation(PreAuthorize.class).value();
		for (Role role : Role.values()) {
			assertThat(gate.contains("'" + role.name() + "'")).as(role.name())
					.isEqualTo(PayoutService.MAY_RECORD.contains(role));
		}
	}

	@Test
	void theRegisterForwardsItsFiltersAndIsNotMistakenForAPayoutId() throws Exception {
		given(register.rows(any())).willReturn(List.of());
		UUID expert = UUID.randomUUID();

		mockMvc.perform(get("/api/payouts/cases?status=PENDING&expertId=" + expert + "&q=ada")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isOk());

		verify(register).rows(new Filter(RegisterStatus.PENDING, expert, null, null, "ada", null));
	}

	@Test
	void theExpertsAndOverviewRoutesAnswer() throws Exception {
		given(register.experts(any())).willReturn(List.of());
		given(register.overview(any(), any(), any())).willReturn(List.of());
		mockMvc.perform(get("/api/payouts/experts").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/payouts/overview?range=custom&from=2026-09-01&to=2026-09-30")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.BRAND_MANAGER)))
				.andExpect(status().isOk());
		verify(register).overview(java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-09-30"), null);
	}

	@Test
	void theOverviewRefusesDatesOnANamedRange() throws Exception {
		// The shell's contract (DateWindow): explicit dates only with range=custom.
		mockMvc.perform(get("/api/payouts/overview?range=month&from=2026-09-01&to=2026-09-30")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "PROJECT_MANAGER", "PROJECT_COORDINATOR", "CASE_MANAGER", "SALES",
			"MARKETING" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/payouts/cases").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
	}

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}
}
