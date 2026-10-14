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
import com.ie.evalos.service.SettingsAdminService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

	@MockitoBean
	SettingsAdminService admin;

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

	/** D83: the Administrator edits name, currency and payout term — and the answer still carries no secret. */
	@Test
	void theAdministratorEditsABrandsDetails() throws Exception {
		UUID id = UUID.randomUUID();
		Brand edited = brand("Intl Evaluations", "international-evaluations");
		given(edited.getCurrency()).willReturn("USD");
		given(edited.getPayoutTermDays()).willReturn(14);
		given(admin.updateBrand(eq(id), any())).willReturn(edited);

		mockMvc.perform(put("/api/brands/" + id).header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Intl Evaluations\",\"currency\":\"usd\",\"payoutTermDays\":14}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("Intl Evaluations"))
				.andExpect(jsonPath("$.data.payoutTermDays").value(14))
				.andExpect(jsonPath("$.data.webhookEndpointToken").doesNotExist());
		verify(admin).updateBrand(id, new SettingsAdminService.BrandChange("Intl Evaluations", "usd", 14));
	}

	/** The GM reads brands but does not edit them; nobody else reaches the route at all. */
	@Test
	void onlyTheAdministratorEditsABrand() throws Exception {
		for (Role role : List.of(Role.GM, Role.BRAND_MANAGER, Role.CASE_MANAGER)) {
			mockMvc.perform(put("/api/brands/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(role))
					.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"payoutTermDays\":7}"))
					.andExpect(status().isForbidden());
		}
		verify(admin, never()).updateBrand(any(), any());
	}

	@Test
	void anUnauthenticatedCallerIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/brands"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}
}
