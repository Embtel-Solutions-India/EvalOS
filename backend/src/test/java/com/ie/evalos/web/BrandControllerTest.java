package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.BrandQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The brand switcher's endpoint is the one cross-brand read in the app, so the only
 * thing worth asserting is who cannot reach it — and that the response carries no
 * secret.
 */
@WebMvcTest(controllers = BrandController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class BrandControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	BrandQueryService brands;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private static Brand brand(String name, String slug) {
		Brand value = mock(Brand.class);
		given(value.getId()).willReturn(UUID.randomUUID());
		given(value.getName()).willReturn(name);
		given(value.getSlug()).willReturn(slug);
		return value;
	}

	@Test
	void theGmGetsEveryBrandAndNoSecrets() throws Exception {
		List<Brand> all = List.of(
				brand("International Evaluations", "international-evaluations"),
				brand("XpertsPortal", "xpertsportal"));
		given(brands.selectable()).willReturn(all);

		mockMvc.perform(get("/api/brands").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].name").value("International Evaluations"))
				.andExpect(jsonPath("$.data[0].slug").value("international-evaluations"))
				// The switcher needs three fields. The webhook endpoint token is on the same
				// entity and must never leave it (invariants 4 and 11) — and it matters more
				// since the inbound HMAC was dropped: that token is now the webhook's whole
				// credential, so leaking it here would hand anyone the ability to open cases.
				.andExpect(jsonPath("$.data[0].webhookEndpointToken").doesNotExist());
	}

	/** Knowing the shape of the business is itself cross-brand information. */
	@Test
	void everyOtherRoleIsForbidden() throws Exception {
		for (Role role : List.of(Role.BRAND_MANAGER, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
				Role.CASE_MANAGER, Role.EXPERT_NETWORK_MANAGER)) {
			mockMvc.perform(get("/api/brands").header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void anUnauthenticatedCallerIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/brands"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}
}
