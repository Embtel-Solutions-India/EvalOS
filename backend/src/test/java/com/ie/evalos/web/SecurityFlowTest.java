package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.AuthService;
import com.ie.evalos.service.TeamMemberQueryService;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The acceptance criteria of Unit 02, over the real filter chain: login issues a
 * token, the token carries the tenant, and role/authentication gates hold.
 *
 * <p>The repository is mocked because this machine has no Postgres — what the
 * scope predicate actually filters on is asserted in {@code ScopePredicateTest}.
 */
@WebMvcTest(controllers = { AuthController.class, TeamMemberController.class })
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class, AuthService.class, TeamMemberQueryService.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class SecurityFlowTest {

	/** BCrypt of {@code DevPassw0rd!} — the same throwaway hash as the local seed. */
	private static final String PASSWORD = "DevPassw0rd!";
	private static final String PASSWORD_HASH = "$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW";

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TEAM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private static final StaffPrincipal GM = new StaffPrincipal(
			UUID.randomUUID(), "gm@evalos.local", "Grace Moreau", Role.GM, null, null, PASSWORD_HASH, true);
	private static final StaffPrincipal BRAND_MANAGER = new StaffPrincipal(
			UUID.randomUUID(), "bm.ie@evalos.local", "Brandon Iyer", Role.BRAND_MANAGER, BRAND_IE, null,
			PASSWORD_HASH, true);
	private static final StaffPrincipal CASE_MANAGER = new StaffPrincipal(
			UUID.randomUUID(), "cm.ie@evalos.local", "Chris Mabry", Role.CASE_MANAGER, BRAND_IE, TEAM,
			PASSWORD_HASH, true);
	private static final StaffPrincipal PROJECT_MANAGER = new StaffPrincipal(
			UUID.randomUUID(), "pm.ie@evalos.local", "Priya Menon", Role.PROJECT_MANAGER, BRAND_IE, TEAM,
			PASSWORD_HASH, true);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@MockitoBean
	TeamMemberRepository teamMembers;

	private String bearer(StaffPrincipal principal) {
		return "Bearer " + jwtService.issue(principal);
	}

	private static String loginBody(String email, String password) {
		return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
	}

	@Test
	void seededGmLogsInAndReceivesAToken() throws Exception {
		given(userDetailsService.loadUserByUsername("gm@evalos.local")).willReturn(GM);

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("gm@evalos.local", PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				// Three dot-separated JWT segments.
				.andExpect(jsonPath("$.data.token").value(Matchers.matchesPattern("[\\w-]+\\.[\\w-]+\\.[\\w-]+")))
				.andExpect(jsonPath("$.data.role").value("GM"))
				.andExpect(jsonPath("$.data.brandId").doesNotExist());
	}

	@Test
	void wrongPasswordIsRejectedWithoutSayingWhy() throws Exception {
		given(userDetailsService.loadUserByUsername("gm@evalos.local")).willReturn(GM);

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("gm@evalos.local", "not-the-password")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void unknownEmailLooksExactlyLikeAWrongPassword() throws Exception {
		given(userDetailsService.loadUserByUsername("nobody@evalos.local"))
				.willThrow(new UsernameNotFoundException("Bad credentials"));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("nobody@evalos.local", PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void meReflectsTheRoleAndBrandFromTheToken() throws Exception {
		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(BRAND_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(BRAND_MANAGER.memberId().toString()))
				.andExpect(jsonPath("$.data.displayName").value("Brandon Iyer"))
				.andExpect(jsonPath("$.data.role").value("BRAND_MANAGER"))
				.andExpect(jsonPath("$.data.brandId").value(BRAND_IE.toString()));
	}

	@Test
	void meReturnsNoBrandForTheGm() throws Exception {
		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("GM"))
				.andExpect(jsonPath("$.data.brandId").doesNotExist());
	}

	@Test
	void noTokenIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void tamperedTokenIsUnauthenticated() throws Exception {
		String token = jwtService.issue(GM);
		// Flip the first character of the signature, not the last: base64url of a
		// 32-byte HMAC is 43 characters, so the final one carries only four
		// meaningful bits and a flip there can decode to the same signature and
		// still verify. The first character is fully significant.
		int signature = token.lastIndexOf('.') + 1;
		String tampered = token.substring(0, signature)
				+ (token.charAt(signature) == 'A' ? 'B' : 'A')
				+ token.substring(signature + 1);

		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void caseManagerMayNotListTeamMembers() throws Exception {
		mockMvc.perform(get("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(CASE_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void brandManagerListsThroughTheScopedQuery() throws Exception {
		willReturn(List.of()).given(teamMembers).findAll(any(Specification.class));

		mockMvc.perform(get("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(BRAND_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		// The read went through a Specification, never an unscoped findAll().
		verify(teamMembers).findAll(ArgumentMatchers.<Specification<TeamMember>>any());
	}

	@Test
	void gmListsThroughTheScopedQueryToo() throws Exception {
		willReturn(List.of()).given(teamMembers).findAll(any(Specification.class));

		mockMvc.perform(get("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(GM)))
				.andExpect(status().isOk());

		verify(teamMembers).findAll(ArgumentMatchers.<Specification<TeamMember>>any());
	}

	/**
	 * The assignment picker's roster read (Unit 08). A Project Manager assigns Case Managers
	 * and Coordinators, so they may read this — while still being refused the staff directory
	 * above, which is why the two are separate routes with separate projections.
	 */
	@Test
	void aProjectManagerMayReadTheAssignableRosterButNotTheDirectory() throws Exception {
		willReturn(List.of()).given(teamMembers).findAll(any(Specification.class));

		mockMvc.perform(get("/api/team-members/assignable")
				.param("role", "CASE_MANAGER")
				.header(HttpHeaders.AUTHORIZATION, bearer(PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		// Still scoped: a Specification, never an unscoped findAll().
		verify(teamMembers).findAll(ArgumentMatchers.<Specification<TeamMember>>any());

		mockMvc.perform(get("/api/team-members").header(HttpHeaders.AUTHORIZATION, bearer(PROJECT_MANAGER)))
				.andExpect(status().isForbidden());
	}

	@Test
	void aCaseManagerMayNotReadTheAssignableRosterEither() throws Exception {
		mockMvc.perform(get("/api/team-members/assignable")
				.param("role", "CASE_MANAGER")
				.header(HttpHeaders.AUTHORIZATION, bearer(CASE_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}
}
