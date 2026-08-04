package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.DriveUnavailableException;
import com.ie.evalos.integration.GoogleDriveClient;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertLoadService.Load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 13's acceptance criteria, in the service that owns them.
 *
 * <p>The load-bearing one is the first: <strong>redaction is asserted by seeding every free-text
 * field with a distinctive token and searching the rendered output for each</strong>, rather than
 * by checking that the fields we remembered to exclude are excluded. The difference matters —
 * the second kind of test passes forever while a field added in a later unit leaks.
 *
 * <p>{@code Case} and {@code Expert} are mocked, as in {@code ExpertMatchServiceTest}: the
 * reference label is a function of the two ids, and a freshly-constructed entity has no id until
 * something persists it. Brand isolation is the two scoped reads this service delegates to, and
 * is asserted where those live.
 */
class RedactedProfileServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	/**
	 * Fixed rather than random, and this is deliberate. Two of the criteria are about the
	 * reference label — stable per case, different between two cases — and the label is a
	 * two-letter digest, so a pair of random ids would collide about once in 676 runs. A test
	 * that fails one morning in 676 gets deleted rather than investigated.
	 */
	private static final UUID CASE_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	private static final UUID OTHER_CASE_ID = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

	private static final UUID EXPERT_ID = UUID.fromString("cccccccc-0000-4000-8000-000000000003");

	/**
	 * One token per excluded field, each unmistakable in a haystack of HTML. Real-looking values
	 * would risk a false pass: "Smith" could plausibly appear in boilerplate, {@code ZZQ_NAME_ZZQ}
	 * could not.
	 */
	private static final String NAME_TOKEN = "ZZQNAMEZZQ";
	private static final String INSTITUTION_TOKEN = "ZZQINSTITUTIONZZQ";
	private static final String EMAIL_TOKEN = "ZZQEMAILZZQ";
	private static final String PHONE_TOKEN = "ZZQPHONEZZQ";
	private static final String NOTES_TOKEN = "ZZQNOTESZZQ";
	private static final String SOURCE_TOKEN = "ZZQSOURCEZZQ";
	private static final String PAYMENT_TOKEN = "ZZQPAYMENTZZQ";

	private final CaseLifecycleService cases = mock(CaseLifecycleService.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final ExpertLoadService loads = mock(ExpertLoadService.class);
	private final AuditService audit = mock(AuditService.class);
	private final GoogleDriveClient drive = mock(GoogleDriveClient.class);

	private final RedactedProfileService profiles =
			new RedactedProfileService(cases, experts, loads, audit, drive);

	private final UUID actor = UUID.randomUUID();

	@BeforeEach
	void aPaidCaseWithAnAssignedExpert() {
		// Each mock is fully built into a local *before* it is handed to a stubbing. Building
		// one inside a `willReturn(...)` argument list stubs the inner mock while the outer
		// stubbing is still open, which Mockito reports as UnfinishedStubbing — the same trap
		// CaseLifecycleServiceTest and ExpertMatchServiceTest both note.
		Case paidCase = subject(CASE_ID, true, "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrS");
		Expert expert = tokenisedExpert();

		given(cases.read(CASE_ID)).willReturn(paidCase);
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(expert));
		given(loads.forExpert(EXPERT_ID)).willReturn(new Load(2, 17));
		given(loads.forExperts(anyCollection())).willReturn(Map.of(EXPERT_ID, new Load(2, 17)));

		StaffPrincipal principal = new StaffPrincipal(actor, "pm@evalos.local", "PM",
				Role.PROJECT_MANAGER, BRAND, UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	// --- fixtures ------------------------------------------------------------

	private Case subject(UUID id, boolean paid, String driveLink) {
		Case value = mock(Case.class);
		given(value.getId()).willReturn(id);
		given(value.getBrandId()).willReturn(BRAND);
		given(value.getCaseCode()).willReturn("IE-2026-0001");
		given(value.getExpertId()).willReturn(EXPERT_ID);
		given(value.isPaid()).willReturn(paid);
		given(value.getDriveLink()).willReturn(driveLink);
		return value;
	}

	/** Every excluded field carries its token; the credentials carry real values. */
	private Expert tokenisedExpert() {
		Expert value = mock(Expert.class);
		given(value.getId()).willReturn(EXPERT_ID);
		given(value.getBrandId()).willReturn(BRAND);
		given(value.getFullName()).willReturn(NAME_TOKEN);
		given(value.getInstitution()).willReturn(INSTITUTION_TOKEN);
		given(value.getEmail()).willReturn(EMAIL_TOKEN);
		given(value.getPhone()).willReturn(PHONE_TOKEN);
		given(value.getNotes()).willReturn(NOTES_TOKEN);
		given(value.getRecruitmentSource()).willReturn(SOURCE_TOKEN);
		given(value.getPaymentDetail()).willReturn(PAYMENT_TOKEN);
		given(value.getQualityScore()).willReturn(new BigDecimal("9.4"));
		given(value.getAvgResponseHours()).willReturn(new BigDecimal("6.5"));
		given(value.getPerformanceFlags()).willReturn(List.of(PerformanceFlag.SLOW_RESPONSE));
		// What may appear.
		given(value.getTitle()).willReturn("Professor of Mechanical Engineering");
		given(value.getTier()).willReturn(ExpertTier.TIER_1);
		given(value.getPrimaryFields()).willReturn(List.of(FieldTag.MECHANICAL_ENGINEERING));
		given(value.getSecondaryFields()).willReturn(List.of(FieldTag.COMPUTER_SCIENCE));
		given(value.getLetterTypes()).willReturn(List.of(LetterType.EXPERT_OPINION_LETTER));
		given(value.getDateOnboarded()).willReturn(LocalDate.now().minusYears(6));
		return value;
	}

	// --- the redaction -------------------------------------------------------

	/** Acceptance criterion 1, asserted by search rather than by recollection. */
	@Test
	void theRedactedProfileCarriesNoIdentifyingFieldAndNoFreeText() {
		String html = profiles.redacted(CASE_ID).html();

		assertThat(html).doesNotContain(NAME_TOKEN, INSTITUTION_TOKEN, EMAIL_TOKEN, PHONE_TOKEN,
				NOTES_TOKEN, SOURCE_TOKEN);
	}

	/** Criterion 2. Not blanked, not masked — never rendered, on either profile. */
	@Test
	void paymentDetailAppearsOnNeitherProfile() {
		assertThat(profiles.redacted(CASE_ID).html()).doesNotContain(PAYMENT_TOKEN);
		assertThat(profiles.full(CASE_ID).html()).doesNotContain(PAYMENT_TOKEN);
	}

	/**
	 * The internal assessments are excluded from the client-facing document too. Not one of the
	 * spec's numbered criteria but the same table's right-hand column, and the reason the
	 * whitelist is a whitelist.
	 */
	@Test
	void theInternalAssessmentsStayInternal() {
		String html = profiles.redacted(CASE_ID).html();

		assertThat(html).doesNotContain("9.4");
		assertThat(html).doesNotContain("6.5");
		assertThat(html).doesNotContain("SLOW_RESPONSE", "Slow Response");
	}

	/** What the document is actually for: it has to be worth reading. */
	@Test
	void theRedactedProfileStillCarriesTheCredentialsAndTheDerivedCaseCount() {
		String html = profiles.redacted(CASE_ID).html();

		assertThat(html).contains("Professor of Mechanical Engineering");
		assertThat(html).contains("Tier 1");
		assertThat(html).contains("Mechanical Engineering");
		assertThat(html).contains("Computer Science");
		assertThat(html).contains("Expert Opinion Letter");
		// 17 is ExpertLoadService's derived count, not expert.total_cases_completed — which is a
		// permanent zero, so a profile reading "0" would be this line failing to hold.
		assertThat(html).contains("17");
		assertThat(html).contains("6 years on the panel");
	}

	/**
	 * {@code title} is the one free-text field that survives, so it is also the one that can
	 * carry markup. It is escaped, which is what makes the panel's sandboxed iframe a second
	 * layer rather than the only one.
	 */
	@Test
	void aTitleCarryingMarkupIsEscapedRatherThanRendered() {
		Expert scripted = tokenisedExpert();
		given(scripted.getTitle()).willReturn("<script>alert('x')</script>");
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(scripted));

		String html = profiles.redacted(CASE_ID).html();

		assertThat(html).doesNotContain("<script>");
		assertThat(html).contains("&lt;script&gt;");
	}

	// --- the reference label -------------------------------------------------

	/** Criterion 3, both halves. */
	@Test
	void theReferenceIsStablePerCaseAndDiffersBetweenCases() {
		assertEquals(profiles.redacted(CASE_ID).reference(), profiles.redacted(CASE_ID).reference());

		Case other = subject(OTHER_CASE_ID, true, null);
		given(cases.read(OTHER_CASE_ID)).willReturn(other);

		assertThat(profiles.redacted(OTHER_CASE_ID).reference())
				.isNotEqualTo(profiles.redacted(CASE_ID).reference());
	}

	/** The label is what the document is headed with, not merely what the API reports. */
	@Test
	void theReferenceIsWhatTheAnonymousDocumentIsHeadedWith() {
		RedactedProfileService.Profile profile = profiles.redacted(CASE_ID);

		assertThat(profile.reference()).matches("Expert [A-Z][A-Z]");
		assertThat(profile.html()).contains(profile.reference());
	}

	/** No sequence anywhere: a numbered label would tell the client they were the fourth choice. */
	@Test
	void theReferenceCarriesNoOrderingInformation() {
		assertThat(profiles.redacted(CASE_ID).reference()).doesNotContainPattern("\\d");
	}

	// --- the paid gate -------------------------------------------------------

	/** Criterion 4, the refusing half. */
	@Test
	void theFullProfileIsRefusedOnAnUnpaidCase() {
		Case unpaid = subject(CASE_ID, false, null);
		given(cases.read(CASE_ID)).willReturn(unpaid);

		assertThatThrownBy(() -> profiles.full(CASE_ID))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining("not been paid");
	}

	/** Criterion 4, the releasing half — and it releases the identity, which is the point. */
	@Test
	void theFullProfileOnAPaidCaseNamesTheExpert() {
		String html = profiles.full(CASE_ID).html();

		assertThat(html).contains(NAME_TOKEN, INSTITUTION_TOKEN, EMAIL_TOKEN, PHONE_TOKEN);
		// Still not the free text, and still not the internal assessments: "full" means identity
		// and credentials, not the roster row.
		assertThat(html).doesNotContain(NOTES_TOKEN, SOURCE_TOKEN, PAYMENT_TOKEN);
	}

	/** The redacted profile has no such gate — approval happens before the money. */
	@Test
	void theRedactedProfileIsAvailableOnAnUnpaidCase() {
		Case unpaid = subject(CASE_ID, false, null);
		given(cases.read(CASE_ID)).willReturn(unpaid);

		assertThat(profiles.redacted(CASE_ID).html()).contains("Professor of Mechanical Engineering");
	}

	// --- the Drive write -----------------------------------------------------

	/** Criterion 6: the case's own folder, the returned link, and the audit row. */
	@Test
	void theDriveWriteFilesIntoTheCasesOwnFolderAndIsAudited() {
		given(drive.uploadHtmlAsDoc(anyString(), anyString(), anyString()))
				.willReturn(new GoogleDriveClient.Uploaded("drive-file-9", "https://docs.google.com/document/d/9"));

		RedactedProfileService.DriveWrite written = profiles.writeRedactedToDrive(CASE_ID);

		assertEquals("drive-file-9", written.fileId());
		assertEquals("https://docs.google.com/document/d/9", written.link());

		ArgumentCaptor<String> folder = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(drive).uploadHtmlAsDoc(folder.capture(), name.capture(), body.capture());
		assertEquals("1AbCdEfGhIjKlMnOpQrS", folder.getValue());
		assertThat(name.getValue()).contains("IE-2026-0001");
		// What is uploaded is the redacted document, not the full one. Worth its own assertion:
		// this is the one path that publishes toward the client.
		assertThat(body.getValue()).doesNotContain(NAME_TOKEN, EMAIL_TOKEN);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> snapshot = ArgumentCaptor.forClass(Map.class);
		verify(audit).recordEvent(eq("CASE"), eq(CASE_ID), eq(AuditAction.EXPORTED), eq(actor),
				eq(null), snapshot.capture());
		assertEquals("drive-file-9", snapshot.getValue().get("driveFileId"));
		assertEquals("1AbCdEfGhIjKlMnOpQrS", snapshot.getValue().get("driveFolderId"));
	}

	/**
	 * Criterion 5, and the half that matters is the second: <strong>nothing is written</strong>.
	 * A fallback folder would file the document somewhere nobody looks, or somewhere another
	 * brand can see it — a cross-brand leak outside the database that no predicate can close.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"https://drive.google.com/",
			"https://drive.google.com/drive/my-drive",
			"https://example.com/some/other/place",
			"not a url at all" })
	void anUnusableDriveLinkIsRefusedAndNothingIsUploaded(String link) {
		Case badLink = subject(CASE_ID, true, link);
		given(cases.read(CASE_ID)).willReturn(badLink);

		assertThatThrownBy(() -> profiles.writeRedactedToDrive(CASE_ID))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining(link);

		verify(drive, never()).uploadHtmlAsDoc(anyString(), anyString(), anyString());
		verify(audit, never()).recordEvent(anyString(), any(), any(), any(), any(), any());
	}

	/** The same refusal for a case with no link at all, and the message does not quote a null. */
	@Test
	void aCaseWithNoDriveLinkIsRefusedAndNothingIsUploaded() {
		Case noLink = subject(CASE_ID, true, null);
		given(cases.read(CASE_ID)).willReturn(noLink);

		assertThatThrownBy(() -> profiles.writeRedactedToDrive(CASE_ID))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining("no Google Drive folder link");

		verify(drive, never()).uploadHtmlAsDoc(anyString(), anyString(), anyString());
	}

	/** Both shapes the spec names. Parsed, not assumed. */
	@ParameterizedTest
	@ValueSource(strings = {
			"https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrS",
			"https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrS?usp=sharing",
			"https://drive.google.com/drive/u/0/folders/1AbCdEfGhIjKlMnOpQrS",
			"https://drive.google.com/open?id=1AbCdEfGhIjKlMnOpQrS" })
	void bothFolderLinkShapesYieldTheId(String link) {
		assertEquals(Optional.of("1AbCdEfGhIjKlMnOpQrS"), RedactedProfileService.folderIdOf(link));
	}

	/**
	 * A Drive failure changes nothing in EvalOS: no audit row claiming a document exists, and
	 * the exception is the upstream one, so the route answers 502 rather than 500.
	 */
	@Test
	void aDriveFailureLeavesNoTrailClaimingTheDocumentExists() {
		given(drive.uploadHtmlAsDoc(anyString(), anyString(), anyString()))
				.willThrow(new DriveUnavailableException("Google Drive did not accept the document"));

		assertThatThrownBy(() -> profiles.writeRedactedToDrive(CASE_ID))
				.isInstanceOf(DriveUnavailableException.class);

		verify(audit, never()).recordEvent(anyString(), any(), any(), any(), any(), any());
	}

	// --- the case with nothing to redact -------------------------------------

	/**
	 * A case with no expert is a refusal, not an empty document. A profile headed "Expert AK"
	 * with every field blank is worse than a 409, because it looks like it worked.
	 */
	@Test
	void aCaseWithNoExpertHasNoProfileToGenerate() {
		Case unassigned = mock(Case.class);
		given(unassigned.getId()).willReturn(CASE_ID);
		given(unassigned.getExpertId()).willReturn(null);
		given(cases.read(CASE_ID)).willReturn(unassigned);

		assertThatThrownBy(() -> profiles.redacted(CASE_ID))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining("no expert is assigned");
	}
}
