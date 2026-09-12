package com.ie.evalos.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.integration.DocumentStoreUnavailableException;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the expert is shown, and what the signature actually records (Unit 15).
 *
 * <p>Two properties carry most of this unit's weight and both are asserted as greps rather than as
 * field lists: <strong>the expert's whitelist excludes what belongs to somebody else</strong>, and
 * <strong>the audit row names the EXPERT</strong> rather than a staff member recording a claim
 * about one. With no signature provider, that row plus the hash plus the attestation is the whole
 * provenance model.
 */
class ExpertPortalServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID CONTACT_ID = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();

	private static final byte[] PDF = "%PDF-1.7 signed".getBytes(StandardCharsets.UTF_8);
	/** SHA-256 of {@link #PDF}, so "it hashed something" cannot pass for "it hashed the bytes". */
	private static final String SHA_256_OF_PDF =
			"281a9b6b403e3fecbd92e4b0504bde1ddac566e0478eb038d63a85aed1a8b418";

	private static final String ATTESTATION = "I, Dr Ada Lovelace, confirm this is my signature on this letter.";

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);
	private final DocumentStore store = mock(DocumentStore.class);
	private final AuditService audit = mock(AuditService.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final PayoutPaymentRepository payments = mock(PayoutPaymentRepository.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ExpertPortalService portal = new ExpertPortalService(cases, contacts, experts, payouts, payments,
			checklistItems, documents, lifecycle, sla, store, audit);

	private Case subject;

	private static PortalPrincipal tokenFor(UUID brandId, UUID caseId) {
		return tokenFor(brandId, caseId, EXPERT_ID);
	}

	/** A token names the expert it was minted for (V37), which {@code authorized} now checks. */
	private static PortalPrincipal tokenFor(UUID brandId, UUID caseId, UUID expertId) {
		return new PortalPrincipal(UUID.randomUUID(), brandId, caseId, PortalAudience.EXPERT, expertId);
	}

	private PortalPrincipal token() {
		return tokenFor(BRAND, CASE_ID);
	}

	@BeforeEach
	void aCaseSittingWithTheExpert() {
		subject = new Case(BRAND, "IE-2026-0044", Stage.EXPERT_SIGNING);
		ReflectionTestUtils.setField(subject, "id", CASE_ID);
		subject.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		subject.setVisaCategory(VisaCategory.EB1A);
		subject.setApplicantName("Priya Menon");
		subject.setDraftLink("https://docs.google.com/document/d/draft/edit");
		subject.setExpertId(EXPERT_ID);
		subject.setExpertSignStatus(ExpertSignStatus.PENDING);
		subject.setContactId(CONTACT_ID);

		// Everything the expert may not see, populated — so a widening has something real to leak
		// rather than a null that would pass by luck.
		subject.setDealValue(new BigDecimal("1450.00"));
		subject.setPmStrategyNotes("lead with the publications");
		subject.setExpertSelectionRationale("cheapest of the three");
		subject.setInvoiceRef("INV-0044");
		subject.setCampaignAttribution("google-ads/spring");
		subject.setAssignedPm(UUID.randomUUID());
		subject.setAssignedCm(UUID.randomUUID());

		Expert expert = new Expert(BRAND, "Dr Ada Lovelace");
		ContactSnapshot contact = new ContactSnapshot(BRAND, "ghl-44");
		contact.syncFromGhl("Anita Rao", "anita@example.test", null, null, null, null, null, null, null);

		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(call -> call.getArgument(0));
		given(experts.findById(EXPERT_ID)).willReturn(Optional.of(expert));
		given(contacts.findById(CONTACT_ID)).willReturn(Optional.of(contact));
		// The database assigns the id on persist, so the mock does what JPA does — the audit row and
		// the response both name the saved row, and a mock that returned an id-less copy would hide
		// that.
		given(documents.save(any(CaseDocument.class))).willAnswer(call -> {
			CaseDocument saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
			return saved;
		});
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.SIGNED_LETTER))
				.willReturn(Optional.empty());
		given(checklistItems.findByCaseId(CASE_ID)).willReturn(List.of(
				supplied("Passport"), outstanding("Employment letter")));
	}

	private DocumentChecklistItem supplied(String label) {
		return new DocumentChecklistItem(BRAND, CASE_ID, label, ChecklistItemStatus.APPROVED);
	}

	private DocumentChecklistItem outstanding(String label) {
		return new DocumentChecklistItem(BRAND, CASE_ID, label, ChecklistItemStatus.REQUIRED);
	}

	private String serialized(Object view) {
		try {
			return objectMapper.writeValueAsString(view);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	// --- the whitelist -------------------------------------------------------

	@Test
	void theExpertSeesTheGoalTheLetterAndWhatTheEvidenceIs() {
		ExpertPortalService.ExpertCaseView view = portal.view(token());

		assertThat(view.caseReference()).isEqualTo("IE-2026-0044");
		assertThat(view.applicantName()).isEqualTo("Priya Menon");
		assertThat(view.serviceType()).isEqualTo(ServiceType.EXPERT_OPINION_LETTER);
		assertThat(view.visaCategory()).isEqualTo(VisaCategory.EB1A);
		assertThat(view.draftLink()).isEqualTo("https://docs.google.com/document/d/draft/edit");
		assertThat(view.awaitingAnswer()).isTrue();
		assertThat(view.signed()).isFalse();
	}

	/** Supplied evidence only. What the client still owes is the Coordinator's business. */
	@Test
	void theEvidenceListIsWhatTheClientActuallySentAndNotWhatTheyStillOwe() {
		assertThat(portal.view(token()).evidence()).containsExactly("Passport");
	}

	/** The criterion, as a grep over the wire format. */
	@Test
	void theViewCarriesNoneOfWhatBelongsToSomebodyElse() {
		String json = serialized(portal.view(token()));

		assertThat(json)
				.doesNotContain("1450.00")
				.doesNotContain("lead with the publications")
				.doesNotContain("cheapest of the three")
				.doesNotContain("INV-0044")
				.doesNotContain("google-ads/spring")
				.doesNotContain("assignedPm")
				.doesNotContain("assignedCm");
	}

	/** Stamped once: "have they opened it at all" is a different question to "when last". */
	@Test
	void theReadReceiptIsStampedOnce() {
		portal.view(token());
		java.time.Instant first = subject.getExpertPortalReadAt();
		assertThat(first).isNotNull();

		portal.view(token());

		assertThat(subject.getExpertPortalReadAt()).isEqualTo(first);
	}

	/** The brand check is a real predicate here, not an argument from provenance. */
	@Test
	void aTokenFromAnotherBrandReachesNothing() {
		assertThatThrownBy(() -> portal.view(tokenFor(OTHER_BRAND, CASE_ID)))
				.isInstanceOf(ForbiddenException.class);
	}

	// --- the signature -------------------------------------------------------

	/**
	 * The part, as an {@link org.springframework.core.io.InputStreamSource} — a fresh stream per
	 * call, which is exactly what a {@code MultipartFile} gives and what the two-pass hash needs.
	 */
	private static org.springframework.core.io.InputStreamSource part(byte[] bytes) {
		return () -> new ByteArrayInputStream(bytes);
	}

	private ExpertPortalService.SignedLetterView upload(byte[] body, String attestation) {
		return portal.uploadSignedLetter(token(), "signed.pdf", body.length, part(body), attestation);
	}

	private ExpertPortalService.SignedLetterView uploadValid() {
		given(lifecycle.expertSignedFromPortal(subject)).willReturn(subject);
		return upload(PDF, ATTESTATION);
	}

	/**
	 * The whole provenance record in one assertion: the file is filed under the case's own signed
	 * prefix, the bytes are hashed <em>as they stream past</em>, the attestation is stored verbatim
	 * with the name it displayed, and the transition fires once.
	 */
	@Test
	void uploadingFilesTheLetterHashesItAndFiresTheTransitionOnce() {
		ExpertPortalService.SignedLetterView view = uploadValid();

		org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(store).put(key.capture(), any(InputStream.class), eq((long) PDF.length), eq("application/pdf"));
		// The case's own signed prefix, brand first — never under the client's, which is what the
		// client sent us rather than what we produced.
		assertThat(key.getValue()).startsWith("%s/case/%s/signed/".formatted(BRAND, CASE_ID));

		verify(lifecycle).expertSignedFromPortal(subject);

		// Hashed in its own pass, so the digest is of the whole file whatever the store does with
		// the stream it is given — including retrying it.
		assertThat(view.contentSha256()).isEqualTo(SHA_256_OF_PDF);
		assertThat(view.version()).isEqualTo(1);
	}

	/** The audit row names the EXPERT. That distinction is the reason this unit exists. */
	@Test
	void theAuditRowNamesTheExpertAndCarriesTheEvidence() {
		uploadValid();

		verify(audit).recordPortalEvent(eq(BRAND), eq(PortalAudience.EXPERT), eq("CASE_DOCUMENT"),
				any(UUID.class), eq(AuditAction.CREATED), eq(null), any());
	}

	/** The row records who uploaded it as an expert, not as a staff member and not as the system. */
	@Test
	void theDocumentRowIsUploadedByAnExpert() {
		uploadValid();

		org.mockito.ArgumentCaptor<CaseDocument> saved = org.mockito.ArgumentCaptor.forClass(CaseDocument.class);
		verify(documents).save(saved.capture());
		assertThat(saved.getValue().getUploadedByType()).isEqualTo(ActorType.EXPERT);
		assertThat(saved.getValue().getKind()).isEqualTo(DocumentKind.SIGNED_LETTER);
		assertThat(saved.getValue().getAttestation()).isEqualTo(ATTESTATION);
		assertThat(saved.getValue().getAttestedName()).isEqualTo("Dr Ada Lovelace");
		assertThat(saved.getValue().getContentSha256()).hasSize(64);
	}

	/** The API is the guard, whatever the UI disables. */
	@Test
	void anUploadWithNoAttestationIsRefused() {
		assertThatThrownBy(() -> upload(PDF, "  ")).isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(store);
		verify(lifecycle, never()).expertSignedFromPortal(any());
	}

	/**
	 * The stored sentence has to be the sentence the server asked for — otherwise the evidence is
	 * a statement the uploader composed about themselves.
	 */
	@Test
	void anAttestationInTheUploadersOwnWordsIsRefused() {
		assertThatThrownBy(() -> upload(PDF, "yeah that's mine")).isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(store);
	}

	/**
	 * A case held for the evidence the expert asked for cannot then be signed — the state machine
	 * refuses it, and it refuses it <strong>before</strong> anything is written to the store.
	 */
	@Test
	void anExpertWaitingOnTheClientCannotSignAndNothingIsFiled() {
		subject.setExceptionState(ExceptionState.ON_HOLD_AWAITING_CLIENT);

		assertThatThrownBy(() -> upload(PDF, ATTESTATION)).isInstanceOf(IllegalTransitionException.class);

		verifyNoInteractions(store);
		verify(documents, never()).save(any());
	}

	/**
	 * Store down → nothing recorded, and the case is left exactly where it was. The reverse order
	 * would mark a case signed with no letter behind it.
	 */
	@Test
	void aStoreFailureLeavesTheCaseUnsignedAndUnchanged() {
		willThrow(new DocumentStoreUnavailableException("down", null))
				.given(store).put(anyString(), any(InputStream.class), anyLong(), anyString());

		assertThatThrownBy(() -> upload(PDF, ATTESTATION)).isInstanceOf(DocumentStoreUnavailableException.class);

		verify(documents, never()).save(any());
		verify(lifecycle, never()).expertSignedFromPortal(any());
		assertThat(subject.getCurrentStage()).isEqualTo(Stage.EXPERT_SIGNING);
		assertThat(subject.getExpertSignStatus()).isEqualTo(ExpertSignStatus.PENDING);
	}

	/**
	 * A second signature is a new version, never an overwrite — a failed final QC sends the letter
	 * back and what returns is a second signature on a second draft. Destroying the first would
	 * destroy the evidence for the case that most needs it.
	 */
	@Test
	void aSecondSignedLetterIsANewVersion() {
		CaseDocument first = new CaseDocument(BRAND, CASE_ID, DocumentKind.SIGNED_LETTER, 1, null,
				ActorType.EXPERT, null);
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.SIGNED_LETTER))
				.willReturn(Optional.of(first));

		assertThat(uploadValid().version()).isEqualTo(2);
	}

	// --- the letter out ------------------------------------------------------

	@Test
	void theLetterIsHandedOverAndTheOpeningIsAudited() {
		assertThat(portal.letterLink(token())).isEqualTo("https://docs.google.com/document/d/draft/edit");

		verify(audit).recordPortalEvent(eq(BRAND), eq(PortalAudience.EXPERT), eq("CASE"), eq(CASE_ID),
				eq(AuditAction.EXPORTED), eq(null), any());
	}

	@Test
	void aCaseWithNoLetterSaysSoRatherThanHandingOverSomethingElse() {
		subject.setDraftLink(null);

		assertThatThrownBy(() -> portal.letterLink(token())).isInstanceOf(IllegalTransitionException.class);
	}

	/**
	 * <strong>The name on the attestation is the case's, not the caller's.</strong>
	 *
	 * <p>The first version of this check compared the sentence against a name the request supplied,
	 * which any consistent pair satisfied — so the row this service calls the evidence could name
	 * somebody who was never on the case. Both halves are asserted: a sentence naming a different
	 * person is refused, and what is stored is the expert EvalOS has on the case.
	 */
	@Test
	void theAttestationMustNameTheCasesOwnExpert() {
		assertThatThrownBy(() -> upload(PDF,
				"I, Someone Else, confirm this is my signature on this letter."))
				.isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(store);
		verify(documents, never()).save(any());
	}

	@Test
	void theStoredNameIsTheExpertOnTheCase() {
		uploadValid();

		org.mockito.ArgumentCaptor<CaseDocument> saved = org.mockito.ArgumentCaptor.forClass(CaseDocument.class);
		verify(documents).save(saved.capture());
		assertThat(saved.getValue().getAttestedName()).isEqualTo("Dr Ada Lovelace");
	}

	/**
	 * <strong>A token is bound to the expert it was minted for (V37), and this is the case the
	 * review found.</strong>
	 *
	 * <p>{@code portal_access} used to name a case and an audience only, so nothing could tell one
	 * expert's token from another's: A declines, the Case Manager rematches to B and sends, and A's
	 * month-long link still accepted, held, declined or uploaded the deliverable against a case that
	 * names B. Revoking on those four transitions closes the paths that exist; this closes the ones
	 * nobody has written yet.
	 */
	@Test
	void aTokenNamingAnotherExpertReachesNothing() {
		PortalPrincipal supersededLink = tokenFor(BRAND, CASE_ID, UUID.randomUUID());

		assertThatThrownBy(() -> portal.view(supersededLink)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> portal.accept(supersededLink)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> portal.letterLink(supersededLink)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> portal.uploadSignedLetter(supersededLink, "signed.pdf", PDF.length,
				part(PDF), ATTESTATION)).isInstanceOf(ForbiddenException.class);

		verifyNoInteractions(store);
		verify(documents, never()).save(any());
	}

	/** Fails closed on a token minted before the column existed, rather than waving it through. */
	@Test
	void aTokenFromBeforeTheColumnReachesNothing() {
		assertThatThrownBy(() -> portal.view(tokenFor(BRAND, CASE_ID, null)))
				.isInstanceOf(ForbiddenException.class);
	}

	/** A case whose expert row has gone has nobody whose signature this could be. */
	@Test
	void aCaseWhoseExpertRecordIsGoneCannotBeSigned() {
		given(experts.findById(EXPERT_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> upload(PDF, ATTESTATION)).isInstanceOf(IllegalTransitionException.class);

		verifyNoInteractions(store);
	}

	/**
	 * The hash does not depend on how the store reads the stream.
	 *
	 * <p>This is the retry case, and it is why the digest is its own pass: the S3 SDK resets and
	 * re-reads a mark-supporting stream on a transient failure, and a digest wrapped around that
	 * read would hash the file twice over. The mock reads the body twice; the recorded hash is
	 * still the hash of the file.
	 */
	@Test
	void aStoreThatRereadsTheStreamDoesNotChangeTheHash() {
		org.mockito.BDDMockito.willAnswer(call -> {
			InputStream given = call.getArgument(1);
			given.readAllBytes();
			return null;
		}).given(store).put(anyString(), any(InputStream.class), anyLong(), anyString());

		assertThat(uploadValid().contentSha256()).isEqualTo(SHA_256_OF_PDF);
	}
}
