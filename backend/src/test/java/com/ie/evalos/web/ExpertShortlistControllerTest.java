package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.ExpertMatchService;
import com.ie.evalos.service.ExpertMatchService.Factor;
import com.ie.evalos.service.ExpertMatchService.ScoredExpert;
import com.ie.evalos.service.ExpertMatchService.Shortlist;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shortlist route's gate and its projection. Two properties worth pinning: a shortlist
 * reveals which case needs which discipline, so the supply-side and case-working roles are
 * refused; and the card carries no trace of the encrypted {@code payment_detail}.
 */
@WebMvcTest(controllers = ExpertShortlistController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class ExpertShortlistControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final String PATH = "/api/cases/" + CASE_ID + "/expert-shortlist";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	ExpertMatchService matching;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private void oneRankedExpert() {
		Expert expert = new Expert(BRAND_IE, "Dr Ada Okoye");
		expert.setInstitution("Lagos Institute of Technology");
		expert.setTier(ExpertTier.TIER_1);
		expert.setAvailability(Availability.AVAILABLE);
		// The one field that must never travel, set so its absence below is a real assertion.
		expert.setPaymentDetail("sort code 00-00-00, account 12345678");

		given(matching.shortlist(any(UUID.class), any(FieldTag.class))).willReturn(new Shortlist(
				List.of(new ScoredExpert(expert, 85,
						List.of(new Factor("Field match", 40, 40, "Primary field"),
								new Factor("Letter-type experience", 25, 25, "Signs this letter type"),
								new Factor("Acceptance rate", 20, 5, "25% of resolved offers accepted"),
								new Factor("Current load", 15, 15, "No open cases")),
						List.of(PerformanceFlag.SLOW_RESPONSE), 0)),
				null));
	}

	@Test
	void aProjectManagerGetsRankedCardsWithTheBreakdownAndNoSecret() throws Exception {
		oneRankedExpert();

		mockMvc.perform(get(PATH)
				.param("fieldTag", "MECHANICAL_ENGINEERING")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.experts.length()").value(1))
				.andExpect(jsonPath("$.data.experts[0].fullName").value("Dr Ada Okoye"))
				.andExpect(jsonPath("$.data.experts[0].institution").value("Lagos Institute of Technology"))
				.andExpect(jsonPath("$.data.experts[0].tier").value("TIER_1"))
				.andExpect(jsonPath("$.data.experts[0].score").value(85))
				// The breakdown, and it adds up to the score above it.
				.andExpect(jsonPath("$.data.experts[0].factors.length()").value(4))
				.andExpect(jsonPath("$.data.experts[0].factors[0].label").value("Field match"))
				.andExpect(jsonPath("$.data.experts[0].factors[0].earned").value(40))
				.andExpect(jsonPath("$.data.experts[0].factors[2].why").value("25% of resolved offers accepted"))
				// The flags are shown, so a PM can weigh what a number would have hidden.
				.andExpect(jsonPath("$.data.experts[0].flags[0]").value("SLOW_RESPONSE"))
				// Invariant 4: not blanked, not masked — not a member of the DTO at all.
				.andExpect(jsonPath("$.data.experts[0].paymentDetail").doesNotExist())
				.andExpect(jsonPath("$.data.experts[0].email").doesNotExist())
				.andExpect(jsonPath("$.data.emptyReason").doesNotExist());
	}

	@Test
	void anEmptyShortlistTellsThePmWhichFactorEmptiedIt() throws Exception {
		given(matching.shortlist(any(UUID.class), any(FieldTag.class))).willReturn(
				new Shortlist(List.of(), "no available expert carries the Mechanical Engineering tag"));

		mockMvc.perform(get(PATH)
				.param("fieldTag", "MECHANICAL_ENGINEERING")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.experts.length()").value(0))
				.andExpect(jsonPath("$.data.emptyReason")
						.value("no available expert carries the Mechanical Engineering tag"));
	}

	/**
	 * Every role but the three that staff a case. The Expert Network Manager owns the roster and
	 * is still refused: the shortlist reveals which case needs which discipline, and supply-side
	 * access does not extend to case content.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class,
			names = { "CASE_MANAGER", "PROJECT_COORDINATOR", "EXPERT_NETWORK_MANAGER" })
	void theRolesThatDoNotStaffCasesAreRefused(Role role) throws Exception {
		mockMvc.perform(get(PATH)
				.param("fieldTag", "MECHANICAL_ENGINEERING")
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "BRAND_MANAGER" })
	void oversightReadsItToo(Role role) throws Exception {
		oneRankedExpert();

		mockMvc.perform(get(PATH)
				.param("fieldTag", "MECHANICAL_ENGINEERING")
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	@Test
	void theFieldTagIsRequiredAndClosed() throws Exception {
		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isBadRequest());

		// Free text would have made the scorer guess; an unknown tag fails loudly instead.
		mockMvc.perform(get(PATH)
				.param("fieldTag", "mechanical engg")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theShortlistIsNotReachableWithoutASession() throws Exception {
		mockMvc.perform(get(PATH).param("fieldTag", "MECHANICAL_ENGINEERING"))
				.andExpect(status().isUnauthorized());
	}
}
