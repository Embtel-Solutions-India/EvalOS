package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.DriveUnavailableException;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.RedactedProfileService;

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
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three routes' gates and the shape of each refusal.
 *
 * <p>The gate worth stating in a test rather than a comment: <strong>the Case Manager reads both
 * profiles and publishes neither</strong>. They draft the letter and need to know who is signing
 * it, but putting an artefact in front of the client is the Project Manager's call — so their
 * presence on the two GETs and absence from the Drive write is one asymmetry, easy to flatten by
 * accident, and it is pinned here.
 */
@WebMvcTest(controllers = ExpertProfileController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class ExpertProfileControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final String BASE = "/api/cases/" + CASE_ID + "/expert-profile";
	private static final String REDACTED = BASE + "/redacted";
	private static final String FULL = BASE + "/full";
	private static final String TO_DRIVE = BASE + "/redacted/to-drive";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	RedactedProfileService profiles;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	private void aGeneratedProfile() {
		given(profiles.redacted(any(UUID.class)))
				.willReturn(new RedactedProfileService.Profile("<html><body>Expert AK</body></html>", "Expert AK"));
		given(profiles.full(any(UUID.class)))
				.willReturn(new RedactedProfileService.Profile("<html><body>Dr Ada Okoye</body></html>", "Expert AK"));
	}

	// --- what each admitted role gets ----------------------------------------

	@Test
	void aProjectManagerGetsTheDocumentAndItsReferenceLabel() throws Exception {
		aGeneratedProfile();

		mockMvc.perform(get(REDACTED).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.html").value("<html><body>Expert AK</body></html>"))
				.andExpect(jsonPath("$.data.reference").value("Expert AK"));
	}

	/**
	 * The Drive write answers Drive's own link, not one EvalOS assembled from the file id — if
	 * the URL shape ever changes, a link we built would keep looking right and stop working.
	 */
	@Test
	void theDriveWriteAnswersTheFileIdAndDrivesOwnLink() throws Exception {
		given(profiles.writeRedactedToDrive(any(UUID.class))).willReturn(
				new RedactedProfileService.DriveWrite("drive-file-9", "https://docs.google.com/document/d/9",
						"Expert AK"));

		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.fileId").value("drive-file-9"))
				.andExpect(jsonPath("$.data.link").value("https://docs.google.com/document/d/9"))
				.andExpect(jsonPath("$.data.reference").value("Expert AK"));
	}

	// --- the gates -----------------------------------------------------------

	/** Both reads: the four roles that work a case, the GM included as everywhere else. */
	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "BRAND_MANAGER", "PROJECT_MANAGER", "CASE_MANAGER" })
	void everyRoleThatWorksACaseMayReadBothProfiles(Role role) throws Exception {
		aGeneratedProfile();

		mockMvc.perform(get(REDACTED).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
		mockMvc.perform(get(FULL).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	/**
	 * The Coordinator chases documents and the ENM owns the roster. Neither is part of getting a
	 * client to approve an expert, and the ENM is refused for the reason the shortlist refuses
	 * them: supply-side access does not extend to case content.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "PROJECT_COORDINATOR", "EXPERT_NETWORK_MANAGER" })
	void theRolesOutsideClientApprovalAreRefusedBothReads(Role role) throws Exception {
		mockMvc.perform(get(REDACTED).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get(FULL).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
	}

	/** Publishing toward the client is the PM's call — the CM reads, and does not publish. */
	@ParameterizedTest
	@EnumSource(value = Role.class,
			names = { "CASE_MANAGER", "PROJECT_COORDINATOR", "EXPERT_NETWORK_MANAGER" })
	void onlyTheCommercialRolesAndThePmMayFileToDrive(Role role) throws Exception {
		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "BRAND_MANAGER", "PROJECT_MANAGER" })
	void theThreeRolesThatMayPublishReachTheDriveWrite(Role role) throws Exception {
		given(profiles.writeRedactedToDrive(any(UUID.class))).willReturn(
				new RedactedProfileService.DriveWrite("f", "https://docs.google.com/document/d/f", "Expert AK"));

		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	@Test
	void noTokenIsUnauthorizedOnAllThree() throws Exception {
		mockMvc.perform(get(REDACTED)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(FULL)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(TO_DRIVE)).andExpect(status().isUnauthorized());
	}

	// --- the refusals --------------------------------------------------------

	/**
	 * A case outside the caller's scope — another brand's, or another Case Manager's.
	 *
	 * <p><strong>403, not the 404 the spec's acceptance criterion names.</strong> Every case route
	 * in EvalOS answers a scoped-read miss with {@code ForbiddenException} (Unit 04's
	 * {@code CaseLifecycleService.load}), and it is uniform across "no such case" and "not your
	 * case", so it is not an existence oracle either way. Making these three routes alone answer
	 * 404 would be the inconsistency; changing all of them is not this unit's scope. Recorded as a
	 * deliberate deviation in the progress tracker.
	 */
	@Test
	void aCaseOutsideTheCallersScopeIsRefusedOnAllThreeRoutes() throws Exception {
		ForbiddenException outOfScope = new ForbiddenException("No case " + CASE_ID + " in this caller's scope");
		given(profiles.redacted(any(UUID.class))).willThrow(outOfScope);
		given(profiles.full(any(UUID.class))).willThrow(outOfScope);
		willThrow(outOfScope).given(profiles).writeRedactedToDrive(any(UUID.class));

		mockMvc.perform(get(REDACTED).header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
				// The message never states whether that id exists somewhere else.
				.andExpect(jsonPath("$.error.message").value("Not permitted for this role, brand, or assignment"));
		mockMvc.perform(get(FULL).header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isForbidden());
		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isForbidden());
	}

	/** The paid gate, as the client sees it: the same 409 shape an illegal transition answers. */
	@Test
	void anUnpaidCaseAnswers409NamingPaymentAsTheReason() throws Exception {
		given(profiles.full(any(UUID.class))).willThrow(new IllegalTransitionException(
				"the case has not been paid, so the expert's full profile is not released yet"));

		mockMvc.perform(get(FULL).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"))
				.andExpect(jsonPath("$.error.message").value(
						"the case has not been paid, so the expert's full profile is not released yet"));
	}

	/** An unusable Drive link is a 409 that quotes the link, so somebody can go and fix the case. */
	@Test
	void anUnusableDriveLinkAnswers409QuotingTheLink() throws Exception {
		willThrow(new IllegalTransitionException("this case's Google Drive link does not name a folder, so there "
				+ "is nowhere to file the profile: https://drive.google.com/"))
				.given(profiles).writeRedactedToDrive(any(UUID.class));

		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.message").value(
						org.hamcrest.Matchers.containsString("https://drive.google.com/")));
	}

	/**
	 * Drive itself failing is a <strong>502</strong>, not a 500: the fault is upstream, and the
	 * distinction is what tells the PM to try again rather than report a bug. Nothing in EvalOS
	 * changed, so there is nothing to undo.
	 */
	@Test
	void driveBeingUnreachableAnswers502AndNot500() throws Exception {
		willThrow(new DriveUnavailableException("Google Drive did not accept the document"))
				.given(profiles).writeRedactedToDrive(any(UUID.class));

		mockMvc.perform(post(TO_DRIVE).header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("DRIVE_UNAVAILABLE"));
	}
}
