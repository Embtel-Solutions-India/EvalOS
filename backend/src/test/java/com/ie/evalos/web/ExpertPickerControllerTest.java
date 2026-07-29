package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assignment picker's expert read. Two things worth pinning: it offers only experts the
 * transition would accept, and it projects nothing but a name and an id — the encrypted
 * {@code payment_detail} lives on this entity and must never leave it.
 */
@WebMvcTest(controllers = ExpertPickerController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class ExpertPickerControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	ExpertRepository experts;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private static Expert expert(String name, Availability availability) {
		Expert value = new Expert(BRAND_IE, name);
		value.setAvailability(availability);
		return value;
	}

	@Test
	void onlyAvailableExpertsAreOfferedAndTheyAreNamedNotSecret() throws Exception {
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(
				expert("Zara Okonkwo", Availability.AVAILABLE),
				expert("Alan Turing", Availability.AVAILABLE),
				expert("Busy Person", Availability.AT_CAPACITY),
				expert("On Leave Person", Availability.ON_LEAVE)));

		mockMvc.perform(get("/api/experts").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				// Offering an expert `availableExpert` would refuse is worse than not offering.
				.andExpect(jsonPath("$.data.length()").value(2))
				// Sorted by name, so the picker is not in insertion order.
				.andExpect(jsonPath("$.data[0].fullName").value("Alan Turing"))
				.andExpect(jsonPath("$.data[1].fullName").value("Zara Okonkwo"))
				// Id and name only — nothing else on this entity may travel.
				.andExpect(jsonPath("$.data[0].paymentDetail").doesNotExist())
				.andExpect(jsonPath("$.data[0].qualityScore").doesNotExist());
	}

	@Test
	void aCaseManagerDoesNotChooseTheExpert() throws Exception {
		// They draft for the expert the PM picked; the roster is not theirs to browse.
		mockMvc.perform(get("/api/experts").header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void thePickerIsNotReachableWithoutASession() throws Exception {
		mockMvc.perform(get("/api/experts"))
				.andExpect(status().isUnauthorized());
	}
}
