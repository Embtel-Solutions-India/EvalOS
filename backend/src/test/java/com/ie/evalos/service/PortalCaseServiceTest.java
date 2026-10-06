package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the client is shown, and what the read does to the case.
 *
 * <p>The whitelist is asserted by <strong>serializing the view and looking for each excluded
 * field</strong>, which is the acceptance criterion as written — a component added to the record
 * later shows up here, and asserting the field list alone would not catch a nested DTO carrying one.
 */
class PortalCaseServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID CONTACT_ID = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
	private final DocumentStore store = mock(DocumentStore.class);
	private final AuditService audit = mock(AuditService.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final CaseDrafts drafts = mock(CaseDrafts.class);
	private final CaseStatusHistory statusHistory = mock(CaseStatusHistory.class);

	private final PortalCaseService portal = new PortalCaseService(cases, contacts, lifecycle, checklistItems, documents,
			store, audit, drafts, statusHistory);

	private Case subject;

	private ContactSnapshot theContact;

	private static PortalPrincipal tokenFor(UUID brandId, UUID caseId) {
		return new PortalPrincipal(UUID.randomUUID(), brandId, caseId, PortalAudience.CLIENT, null);
	}

	@BeforeEach
	void aCaseWithADraftWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
		// The document routes match a row's `case_id` against this, so an unsaved entity's null id
		// would make every one of them refuse for the wrong reason.
		ReflectionTestUtils.setField(subject, "id", CASE_ID);
		subject.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		subject.setDraftLink("https://docs.google.com/document/d/draft/edit");
		subject.setDraftVersionCount(2);
		subject.setClientApprovalStatus(ClientApprovalStatus.PENDING);
		subject.setContactId(CONTACT_ID);

		// Everything the client may not see, populated — so an accidental widening has something
		// real to leak rather than a null that would pass by luck.
		subject.setDealValue(new BigDecimal("1450.00"));
		subject.setPmStrategyNotes("lead with the publications");
		subject.setInvoiceRef("INV-0001");
		subject.setCampaignAttribution("google-ads/spring");
		subject.setAssignedPm(UUID.randomUUID());
		subject.setAssignedCm(UUID.randomUUID());
		subject.setAssignedCoordinator(UUID.randomUUID());

		theContact = new ContactSnapshot(BRAND, "ghl-1");
		theContact.syncFromGhl("Anita Rao", "anita@example.test", null, null, null, null, null, null, null);
		// The upload key is built from this id, so an unsaved entity's null would make the
		// assertion pass on a null-vs-null comparison rather than on the real value.
		ReflectionTestUtils.setField(theContact, "id", CONTACT_ID);

		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(call -> call.getArgument(0));
		given(contacts.findById(CONTACT_ID)).willReturn(Optional.of(theContact));
	}

	/**
	 * An expert with an unmistakable name, so the assertion that nothing about them reaches the
	 * client is a real grep rather than a formality. The portal no longer reads the roster at all
	 * (Unit 13 removed), which is exactly what the test below proves.
	 */
	private void withAnAssignedExpert() {
		subject.setExpertId(UUID.randomUUID());
	}

	private String serialized(PortalCaseService.ClientDraftView view) {
		try {
			return objectMapper.writeValueAsString(view);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A party-scoped token — the only shape Unit 42's sign-in mints for a client who has a GHL
	 * contact. {@code caseId} is null by construction, which is what {@code isPartyScoped()} means.
	 */
	private static PortalPrincipal partyTokenFor(UUID brandId, String ghlContactId) {
		return new PortalPrincipal(UUID.randomUUID(), brandId, null, PortalAudience.CLIENT, null,
				ghlContactId);
	}

	/** The checklist item an upload lands on, pinned to this test's case. */
	private DocumentChecklistItem anItemOnThisCase() {
		DocumentChecklistItem item = new DocumentChecklistItem(BRAND, CASE_ID, "Transcript",
				ChecklistItemStatus.REQUIRED);
		item.markSent(UUID.randomUUID(), java.time.Instant.now());
		UUID itemId = UUID.randomUUID();
		ReflectionTestUtils.setField(item, "id", itemId);
		given(checklistItems.findById(itemId)).willReturn(Optional.of(item));
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.CLIENT_UPLOAD))
				.willReturn(Optional.empty());
		given(documents.save(any(CaseDocument.class))).willAnswer(call -> call.getArgument(0));
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-1"))
				.willReturn(Optional.of(theContact));
		given(cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(BRAND, CONTACT_ID))
				.willReturn(java.util.List.of(subject));
		return item;
	}

	/**
	 * <strong>The regression this class did not have.</strong> {@code upload} resolved its case as
	 * {@code cases.findById(principal.caseId())} while every other method on the class went through
	 * {@code authorized}. A party-scoped token's {@code caseId} is null <em>by definition</em>, so
	 * that call threw and every signed-in client's upload answered 500. It survived because
	 * {@code ClientPortalTest} stubs this service with Mockito and this class had no upload test at
	 * all — so the controller proved the wiring and nothing proved the service.
	 */
	@Test
	void aSignedInClientCanUploadWithAPartyScopedToken() {
		DocumentChecklistItem item = anItemOnThisCase();

		CaseDocument written = portal.upload(partyTokenFor(BRAND, "ghl-1"), CASE_ID, item.getId(),
				"transcript.pdf", "application/pdf", 1024L,
				new java.io.ByteArrayInputStream(new byte[] { 1 }));

		assertThat(written.getCaseId()).isEqualTo(CASE_ID);
		assertThat(written.getKind()).isEqualTo(DocumentKind.CLIENT_UPLOAD);
		assertThat(item.getStatus()).isEqualTo(ChecklistItemStatus.UPLOADED);
	}

	/**
	 * <strong>The key is namespaced by the GHL contact id</strong> — the one identifier that
	 * represents a contact everywhere in this system, ruled 2026-09-17. It was
	 * {@code contact_snapshot.id} from 2026-09-14 until then, after IE's sub-account swap left
	 * post-swap clients with no GHL id to build a key from; what makes it safe again is that the id
	 * now exists before a document can be sent (D3d creates it at set-password, D3c backfills it at
	 * the next sign-in or request, and a request upload happens at submit, which already ensures it).
	 */
	@Test
	void theObjectKeyIsNamespacedByTheGhlContactId() {
		DocumentChecklistItem item = anItemOnThisCase();

		CaseDocument written = portal.upload(partyTokenFor(BRAND, "ghl-1"), CASE_ID, item.getId(),
				"transcript.pdf", "application/pdf", 1024L,
				new java.io.ByteArrayInputStream(new byte[] { 1 }));

		assertThat(written.getObjectKey())
				.startsWith(BRAND + "/client/ghl-1/")
				.doesNotContain(CONTACT_ID.toString());
	}

	/**
	 * <strong>The exposure that comes with keying on a third party's id, pinned rather than
	 * discovered.</strong> A contact with no GHL id — a pre-swap row, or an outage not yet repaired
	 * — has no namespace to write into. It refuses with a message naming the repair (D3c backfills
	 * at the next sign-in or request) instead of inventing a prefix, because a document filed under
	 * a guess is worse than one not filed: nothing would ever look for it there.
	 */
	@Test
	void aContactWithNoGhlIdIsRefusedRatherThanFiledUnderAGuess() {
		ReflectionTestUtils.setField(theContact, "ghlContactId", null);
		DocumentChecklistItem item = anItemOnThisCase();

		// A case-scoped link: a party token names a GHL id, so it could never match this contact.
		assertThatThrownBy(() -> portal.upload(tokenFor(BRAND, CASE_ID), CASE_ID, item.getId(),
				"transcript.pdf", "application/pdf", 1024L,
				new java.io.ByteArrayInputStream(new byte[] { 1 })))
				.hasMessageContaining("no GHL contact id");
	}

	@Test
	void theClientSeesTheirOwnDraftAndNothingAboutTheExpert() {
		withAnAssignedExpert();

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID);

		assertThat(view.clientName()).isEqualTo("Anita Rao");
		assertThat(view.caseReference()).isEqualTo("IE-2026-0001");
		assertThat(view.serviceType()).isEqualTo(ServiceType.EXPERT_OPINION_LETTER);
		assertThat(view.draftLink()).isEqualTo("https://docs.google.com/document/d/draft/edit");
		assertThat(view.draftVersion()).isEqualTo(2);
		assertThat(view.approvalStatus()).isEqualTo(ClientApprovalStatus.PENDING);
		assertThat(view.awaitingAnswer()).isTrue();
	}

	/** The criterion, as a grep over the wire format. */
	@Test
	void theViewCarriesNoneOfWhatBelongsToSomebodyElse() {
		withAnAssignedExpert();

		String json = serialized(portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID));

		assertThat(json)
				.doesNotContain("1450.00")
				.doesNotContain("lead with the publications")
				.doesNotContain("INV-0001")
				.doesNotContain("google-ads/spring")
				.doesNotContain("client-documents")
				.doesNotContain("Ada Lovelace")
				.doesNotContain("assigned");
		assertThat(PortalCaseService.ClientDraftView.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("clientName", "serviceType", "caseReference", "draftLink", "draftVersion",
						"approvalStatus", "awaitingAnswer", "step", "stepIndex", "status", "history");
	}

	/**
	 * A case with no draft link says so, and does <strong>not</strong> fall back to
	 * {@code drive_link} — the folder holding the client's own passport scans, whose contents and
	 * sharing EvalOS does not control. This is the defect Unit 14 had to close first, asserted
	 * rather than commented.
	 */
	@Test
	void aCaseWithNoDraftLinkShowsNothingRatherThanTheDocumentsFolder() {
		subject.setDraftLink(null);

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID);

		assertThat(view.draftLink()).isNull();
		assertThat(serialized(view)).doesNotContain("client-documents");
	}

	/**
	 * <strong>The client is told nothing about the expert at all (Unit 13 removed).</strong>
	 *
	 * <p>This payload used to carry a redacted profile, and the test above asserted the redaction
	 * held. Withholding identity entirely is the stronger position, and this is the assertion that
	 * keeps it: an expert with a very identifiable name is on the case, and no part of them reaches
	 * the wire.
	 */
	@Test
	void nothingAboutTheExpertReachesTheClient() {
		withAnAssignedExpert();

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID);

		assertThat(serialized(view))
				.doesNotContain("Ada Lovelace")
				.doesNotContain("expert");
	}

	/**
	 * The read receipt is stamped once.
	 *
	 * <p>"Has the client seen this at all" is what the Case Manager needs before chasing, and a
	 * value that moved on every visit would answer "when did they last look" instead — which is the
	 * token's {@code last_seen_at}, a different field for a different question.
	 */
	@Test
	void theReceiptIsStampedOnceAndDoesNotMoveOnTheSecondRead() {
		portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID);
		Instant first = subject.getClientPortalReadAt();
		assertThat(first).isNotNull();

		portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID);

		assertThat(subject.getClientPortalReadAt()).isEqualTo(first);
		// The second read writes nothing at all, so it is not a save that happens to be idempotent.
		verify(cases, times(1)).save(any(Case.class));
	}

	/**
	 * The token's brand has to be the case's.
	 *
	 * <p>It cannot currently disagree — {@code brand_id} is {@code updatable = false} on both rows —
	 * so this is the assertion that keeps brand isolation on this surface a real check rather than an
	 * argument from provenance, and that would fail if that ever stopped being true.
	 */
	@Test
	void aTokenWhoseBrandIsNotTheCasesIsRefused() {
		PortalPrincipal crossed = tokenFor(UUID.randomUUID(), CASE_ID);

		assertThatThrownBy(() -> portal.clientView(crossed, CASE_ID)).isInstanceOf(ForbiddenException.class);
		verify(cases, never()).save(any(Case.class));
	}

	@Test
	void aTokenPointingAtANonexistentCaseIsRefused() {
		given(cases.findById(any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> portal.clientView(tokenFor(BRAND, CASE_ID), CASE_ID))
				.isInstanceOf(ForbiddenException.class);
	}

	// -----------------------------------------------------------------------------------------
	// Unit 34c — the client's documents. The upload endpoint shipped in Unit 30 taking a
	// checklistItemId no route revealed, so these two reads are what make it callable.
	// -----------------------------------------------------------------------------------------

	private CaseDocument documentOn(UUID caseId, DocumentKind kind, String filename, String key) {
		CaseDocument row = new CaseDocument(BRAND, caseId, kind, 1, null, ActorType.CLIENT,
				"Passport / Government ID");
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		row.setFilename(filename);
		row.setObjectKey(key);
		return row;
	}

	/** What must be sent, and what has been. The status vocabulary is Unit 10's, unmapped. */
	@Test
	void theClientSeesTheirChecklistAndTheirOwnUploads() {
		DocumentChecklistItem outstanding =
				new DocumentChecklistItem(BRAND, CASE_ID, "Academic Transcript", ChecklistItemStatus.INCORRECT);
		ReflectionTestUtils.setField(outstanding, "id", UUID.randomUUID());
		outstanding.markSent(UUID.randomUUID(), java.time.Instant.now());
		// Unit 61: an item the PC/CM has not sent is not in the client's list at all.
		DocumentChecklistItem unsent =
				new DocumentChecklistItem(BRAND, CASE_ID, "Marriage certificate", ChecklistItemStatus.REQUIRED);
		given(checklistItems.findByCaseId(CASE_ID)).willReturn(java.util.List.of(outstanding, unsent));
		given(documents.findByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.CLIENT_UPLOAD))
				.willReturn(java.util.List.of(documentOn(CASE_ID, DocumentKind.CLIENT_UPLOAD,
						"passport.pdf", "brand/client/ghl-1/doc")));

		PortalCaseService.ClientDocumentsView view = portal.documents(tokenFor(BRAND, CASE_ID), CASE_ID);

		assertThat(view.checklist()).singleElement()
				.satisfies(item -> {
					assertThat(item.label()).isEqualTo("Academic Transcript");
					// INCORRECT reaching the client is the point: it is touchpoint T4 arriving as a
					// state rather than as a message EvalOS has no way to send.
					assertThat(item.status()).isEqualTo(ChecklistItemStatus.INCORRECT);
				});
		assertThat(view.uploaded()).singleElement()
				.satisfies(row -> assertThat(row.filename()).isEqualTo("passport.pdf"));

		// **The object key is an internal address and has no component to travel in.** Asserted on
		// the record rather than on serialized output, so a key added as a field fails here even if
		// it happened to be null in this fixture.
		assertThat(PortalCaseService.UploadedDocumentView.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("id", "filename", "version", "uploadedAt", "checklistLabel");
	}

	@Test
	void aReadUrlIsMintedForTheClientsOwnUploadAndTheOpeningIsAudited() {
		CaseDocument own = documentOn(CASE_ID, DocumentKind.CLIENT_UPLOAD, "passport.pdf", "key/passport");
		given(documents.findById(own.getId())).willReturn(Optional.of(own));
		given(store.presignedUrl("key/passport")).willReturn("https://s3.example/presigned");

		String url = portal.documentUrl(tokenFor(BRAND, CASE_ID), CASE_ID, own.getId(), false);

		assertThat(url).isEqualTo("https://s3.example/presigned");
		// Unit 74: the same upload opens in the browser with its type forced from the filename.
		given(store.presignedView("key/passport", "passport.pdf")).willReturn("https://s3.example/inline");
		assertThat(portal.documentUrl(tokenFor(BRAND, CASE_ID), CASE_ID, own.getId(), true))
				.isEqualTo("https://s3.example/inline");
		verify(audit, times(2)).recordPortalEvent(eq(BRAND), eq(PortalAudience.CLIENT), eq("CASE_DOCUMENT"),
				eq(own.getId()), eq(AuditAction.EXPORTED), any(), any());
	}

	/**
	 * <strong>The kind filter is half the authorization, not a tidy-up.</strong> Without it a
	 * client could name the draft — or, once Unit 15 lands, the expert's signed letter — on their
	 * own case and read it outside the flow that decides when they may. The draft reaches them
	 * through {@code draftLink} on the other screen, under that screen's guard.
	 */
	@Test
	void theDraftAndTheSignedLetterAreNotReachableThroughTheDocumentRoute() {
		for (DocumentKind kind : java.util.List.of(DocumentKind.DRAFT, DocumentKind.SIGNED_LETTER)) {
			CaseDocument notTheirs = documentOn(CASE_ID, kind, "letter.pdf", "key/letter");
			given(documents.findById(notTheirs.getId())).willReturn(Optional.of(notTheirs));

			assertThatThrownBy(() -> portal.documentUrl(tokenFor(BRAND, CASE_ID), CASE_ID, notTheirs.getId(), false))
					.isInstanceOf(ForbiddenException.class);
		}
		// Refused before anything was minted — a URL created ahead of the check has already leaked.
		verify(store, never()).presignedUrl(any());
	}

	@Test
	void aDocumentBelongingToAnotherCaseIsRefusedAndNothingIsMinted() {
		CaseDocument elsewhere = documentOn(UUID.randomUUID(), DocumentKind.CLIENT_UPLOAD,
				"someone-else.pdf", "key/other");
		given(documents.findById(elsewhere.getId())).willReturn(Optional.of(elsewhere));

		assertThatThrownBy(() -> portal.documentUrl(tokenFor(BRAND, CASE_ID), CASE_ID, elsewhere.getId(), false))
				.isInstanceOf(ForbiddenException.class);
		verify(store, never()).presignedUrl(any());
	}

	/** Review Focus 1: the draft id must be on this case and client-visible. */
	@Test
	void aClientCannotReachAReturnedVersionOrAnotherCasesDraft() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID returned = UUID.randomUUID();
		given(drafts.clientVersion(subject, returned)).willThrow(new com.ie.evalos.common.NotFoundException("No such draft"));

		assertThatThrownBy(() -> portal.draftFileUrl(me, CASE_ID, returned, true, false))
				.isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		// Another client's case: the ownership check refuses before the draft is looked at.
		assertThatThrownBy(() -> portal.draftFileUrl(partyTokenFor(BRAND, "ghl-someone-else"), CASE_ID, returned, true, false))
				.isInstanceOf(ForbiddenException.class);
		verify(drafts, times(1)).clientVersion(any(), any());
	}

	@Test
	void approvingNamesTheVersionAndRunsTheExistingTransition() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID v3 = UUID.randomUUID();
		given(lifecycle.clientApproveDraftFromPortal(subject)).willReturn(subject);

		portal.approveDraft(me, CASE_ID, v3);

		verify(drafts).requireInReview(subject, v3);
		verify(lifecycle).clientApproveDraftFromPortal(subject);
	}

	@Test
	void aStaleApprovalNeverReachesTheTransition() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID stale = UUID.randomUUID();
		given(drafts.requireInReview(subject, stale)).willThrow(new com.ie.evalos.common.DraftNotCurrentException("stale"));

		assertThatThrownBy(() -> portal.approveDraft(me, CASE_ID, stale))
				.isInstanceOf(com.ie.evalos.common.DraftNotCurrentException.class);
		assertThatThrownBy(() -> portal.requestChanges(me, CASE_ID, stale, "no"))
				.isInstanceOf(com.ie.evalos.common.DraftNotCurrentException.class);
		verify(lifecycle, never()).clientApproveDraftFromPortal(any());
		verify(lifecycle, never()).clientRequestRevisionsFromPortal(any(), any());
	}

	@Test
	void aClientCommentIsAttributedToTheirCredential() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID v3 = UUID.randomUUID();
		CaseDocument draft = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, 3, null, ActorType.STAFF, null);
		given(drafts.requireInReview(subject, v3)).willReturn(draft);

		portal.addDraftComment(me, CASE_ID, v3, "Page 2", 2);

		verify(drafts).addComment(draft, com.ie.evalos.domain.DraftComment.AuthorKind.CLIENT, me.portalAccessId(),
				"Page 2", 2, false);
	}

	/** Review Focus 4: the signed letter exists at FINAL_QC, and is still not the client's. */
	@Test
	void deliveredFilesAreRefusedBeforeDelivery() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.FINAL_QC);

		assertThatThrownBy(() -> portal.delivered(me, CASE_ID)).isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		assertThatThrownBy(() -> portal.deliveredUrl(me, CASE_ID, UUID.randomUUID(), false))
				.isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		verify(store, never()).presignedUrl(any());
	}

	@Test
	void onceDeliveredTheClientGetsTheSignedLetterAndTheApprovedDraft() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.DELIVERED);
		CaseDocument letter = new CaseDocument(BRAND, CASE_ID, DocumentKind.SIGNED_LETTER, 1, null, ActorType.EXPERT, null);
		ReflectionTestUtils.setField(letter, "id", UUID.randomUUID());
		letter.setObjectKey("signed.pdf");
		letter.setFilename("Signed letter.pdf");
		CaseDocument approved = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, 2, null, ActorType.STAFF, null);
		ReflectionTestUtils.setField(approved, "id", UUID.randomUUID());
		approved.storedDraft("d.docx", "Draft.docx", 1, "d.pdf", "Draft.pdf", 1);
		approved.reviewed(com.ie.evalos.domain.DocumentStatus.CLIENT_APPROVED, null);
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.SIGNED_LETTER))
				.willReturn(Optional.of(letter));
		given(documents.findByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.DRAFT)).willReturn(java.util.List.of(approved));
		given(store.presignedUrl("signed.pdf")).willReturn("https://s3/signed");

		assertThat(portal.delivered(me, CASE_ID)).extracting(PortalCaseService.DeliveredFile::kind)
				.containsExactly("SIGNED_LETTER", "APPROVED_DRAFT");
		assertThat(portal.deliveredUrl(me, CASE_ID, letter.getId(), false)).isEqualTo("https://s3/signed");
		// Unit 74: both delivered files are PDFs, so either opens in the browser's viewer.
		given(store.presignedPdfView("signed.pdf")).willReturn("https://s3/signed-inline");
		given(store.presignedPdfView("d.pdf")).willReturn("https://s3/draft-inline");
		assertThat(portal.deliveredUrl(me, CASE_ID, letter.getId(), true)).isEqualTo("https://s3/signed-inline");
		assertThat(portal.deliveredUrl(me, CASE_ID, approved.getId(), true)).isEqualTo("https://s3/draft-inline");
	}

	@Test
	void theCaseDetailCarriesTheStepAndStatusHistory() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.CLIENT_REVIEW);
		CaseStatusHistory.Entry entry = new CaseStatusHistory.Entry("AWAITING_CLIENT_REVIEW", "Awaiting Client Review", "d", Instant.now(), null);
		given(statusHistory.of(subject)).willReturn(java.util.List.of(entry));

		PortalCaseService.ClientDraftView view = portal.clientView(me, CASE_ID);

		assertThat(view.step()).isEqualTo("Review");
		assertThat(view.stepIndex()).isEqualTo(1);
		assertThat(view.history()).hasSize(1);
		assertThat(view.status()).isSameAs(entry);
	}

	/** Final review #1: a draft uploaded as files, with no link, still opens in the live portal. */
	@Test
	void aDraftWithFilesAndNoLinkStillOpensInTheLivePortal() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setDraftLink(null);
		CaseDocument v3 = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, 3, null, ActorType.STAFF, null);
		v3.storedDraft("d.docx", "Draft.docx", 1, "d.pdf", "Draft.pdf", 1);
		given(drafts.clientVisible(subject)).willReturn(java.util.List.of(v3));
		given(drafts.fileUrl(v3, true)).willReturn("https://s3/draft-v3.pdf");

		assertThat(portal.clientView(me, CASE_ID).draftLink()).isEqualTo("https://s3/draft-v3.pdf");
	}

	/** Final review #3: two simultaneous answers are serialised on the case row, before either reads it. */
	@Test
	void theClientsAnswersLockTheCaseFirst() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID v3 = UUID.randomUUID();
		given(lifecycle.clientApproveDraftFromPortal(subject)).willReturn(subject);
		given(lifecycle.clientRequestRevisionsFromPortal(subject, null)).willReturn(subject);

		portal.approveDraft(me, CASE_ID, v3);
		portal.requestChanges(me, CASE_ID, v3, null);

		verify(cases, times(2)).lockById(CASE_ID);
	}

	/** Unit 61: an unsent item is refused exactly like one on another case. */
	@Test
	void anUnsentItemCannotBeUploadedAgainst() {
		DocumentChecklistItem item = anItemOnThisCase();
		ReflectionTestUtils.setField(item, "sentAt", null);

		assertThatThrownBy(() -> portal.upload(partyTokenFor(BRAND, "ghl-1"), CASE_ID, item.getId(),
				"x.pdf", "application/pdf", 3, new java.io.ByteArrayInputStream(new byte[3])))
				.hasMessageContaining("not on this case");
	}
}
