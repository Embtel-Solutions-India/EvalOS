package com.ie.evalos.web;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.AuthService;
import com.ie.evalos.service.PipelineAssignmentService;
import com.ie.evalos.service.TeamMemberAdminService;
import com.ie.evalos.service.TeamMemberQueryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the logs say, over the real filter chain: one request id on every line a request writes (filter,
 * security, controller, the catch-all handler), refusals and sign-in failures recorded, and none of the
 * secrets a request carries — password, hash, bearer token, email — ever printed.
 */
@WebMvcTest(controllers = { AuthController.class, TeamMemberController.class })
// Boom is imported, not listed: the slice's scan skips classes nested in a test class.
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class, AuthService.class, TeamMemberQueryService.class,
		LoggingHygieneTest.Boom.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
@ExtendWith(OutputCaptureExtension.class)
class LoggingHygieneTest {

	/** BCrypt of {@code DevPassw0rd!}, as in SecurityFlowTest. */
	private static final String PASSWORD = "DevPassw0rd!";
	private static final String PASSWORD_HASH = "$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW";
	private static final String EMAIL = "gm.hygiene@evalos.local";

	private static final StaffPrincipal GM = new StaffPrincipal(
			UUID.randomUUID(), EMAIL, "Grace Moreau", Role.GM, null, null, PASSWORD_HASH, true);
	private static final StaffPrincipal CASE_MANAGER = new StaffPrincipal(
			UUID.randomUUID(), "cm.hygiene@evalos.local", "Chris Mabry", Role.CASE_MANAGER,
			UUID.fromString("11111111-1111-1111-1111-111111111111"), null, PASSWORD_HASH, true);
	private static final StaffPrincipal ADMIN = new StaffPrincipal(
			UUID.randomUUID(), "admin.hygiene@evalos.local", "Alex Admin", Role.ADMIN, null, null, PASSWORD_HASH, true);

	/** A handler that logs and then fails unexpectedly — the path a real bug takes. */
	@RestController
	static class Boom {
		private static final Logger log = LoggerFactory.getLogger(Boom.class);

		@GetMapping("/api/test-boom")
		String boom() {
			log.info("Handling the boom request");
			throw new IllegalStateException("internal detail that must not reach the client");
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@MockitoBean
	PipelineAssignmentService pipelineAssignment;

	@MockitoBean
	TeamMemberRepository teamMembers;

	@MockitoBean
	TeamMemberAdminService admin;

	@MockitoBean
	BrandRepository brands;

	@BeforeEach
	void everyoneIsActive() {
		given(teamMembers.existsByIdAndActiveTrue(ArgumentMatchers.any())).willReturn(true);
	}

	private static List<String> linesWith(CapturedOutput output, String needle) {
		return Arrays.stream(output.getAll().split("\\R")).filter(line -> line.contains(needle)).toList();
	}

	@Test
	void anUnexpectedExceptionIsLoggedWithItsRequestIdAndNotShownToTheCaller(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/test-boom")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(GM))
				.header(RequestIdFilter.HEADER, "req-boom-1"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(RequestIdFilter.HEADER, "req-boom-1"))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Something went wrong"));

		// The same id on the controller's line and on the handler's, so one grep returns the whole request.
		List<String> lines = linesWith(output, "[req-boom-1]");
		assertThat(lines).anySatisfy(line -> assertThat(line).contains("Handling the boom request"));
		assertThat(lines).anySatisfy(line -> assertThat(line).contains("Unhandled exception").contains("[local]"));
		// The exception and its stack go to the log, for whoever is on call.
		assertThat(output.getAll()).contains("IllegalStateException: internal detail that must not reach the client")
				.contains("at com.ie.evalos.web.LoggingHygieneTest$Boom.boom");
	}

	@Test
	void aRefusedSignInIsLoggedWithoutTheAddressOrThePassword(CapturedOutput output) throws Exception {
		given(userDetailsService.loadUserByUsername(EMAIL)).willReturn(GM);

		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header(RequestIdFilter.HEADER, "req-login-bad")
				.content("{\"email\":\"%s\",\"password\":\"Wr0ng-Secret-Value!\"}".formatted(EMAIL)))
				.andExpect(status().isUnauthorized());

		assertThat(linesWith(output, "[req-login-bad]"))
				.anySatisfy(line -> assertThat(line).contains("Staff sign-in refused"));
		assertThat(output.getAll()).doesNotContain("Wr0ng-Secret-Value!").doesNotContain(EMAIL)
				.doesNotContain(PASSWORD_HASH);
	}

	@Test
	void aSuccessfulSignInLogsTheMemberIdButNeverTheTokenOrCredentials(CapturedOutput output) throws Exception {
		given(userDetailsService.loadUserByUsername(EMAIL)).willReturn(GM);

		String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String token = body.replaceFirst("(?s).*\"token\":\"([^\"]+)\".*", "$1");

		assertThat(output.getAll()).contains("Staff sign-in: member " + GM.memberId())
				.doesNotContain(token).doesNotContain(PASSWORD).doesNotContain(PASSWORD_HASH).doesNotContain(EMAIL);
	}

	@Test
	void aRejectedBearerTokenIsLoggedAsARefusalWithoutTheToken(CapturedOutput output) throws Exception {
		String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb3JnZWQifQ.c2lnbmF0dXJlLXRoYXQtd2lsbC1mYWls";

		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
				.header(RequestIdFilter.HEADER, "req-bad-token"))
				.andExpect(status().isUnauthorized());

		assertThat(linesWith(output, "[req-bad-token]"))
				.anySatisfy(line -> assertThat(line).contains("Refused 401 GET /api/me").contains("anonymous"));
		assertThat(output.getAll()).doesNotContain(forged);
	}

	@Test
	void anAuthorizationDenialNamesTheMemberByIdOnly(CapturedOutput output) throws Exception {
		// The chain's own 403 (the Admin's default-deny list) and a handler-level one (@PreAuthorize).
		mockMvc.perform(get("/api/cases").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(ADMIN)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/team-members").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(CASE_MANAGER)))
				.andExpect(status().isForbidden());

		assertThat(output.getAll())
				.contains("Refused 403 GET /api/cases: FORBIDDEN for member " + ADMIN.memberId() + " (ADMIN)")
				.contains("Refused 403")
				.contains("member " + CASE_MANAGER.memberId() + " (CASE_MANAGER)")
				.doesNotContain("admin.hygiene@evalos.local").doesNotContain("cm.hygiene@evalos.local");
	}
}
