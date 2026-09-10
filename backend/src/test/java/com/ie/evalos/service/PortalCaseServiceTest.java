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

	private final PortalCaseService portal = new PortalCaseService(cases, contacts, lifecycle, checklistItems, documents, store, audit);

	private Case subject;

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

		ContactSnapshot contact = new ContactSnapshot(BRAND, "ghl-1");
		contact.syncFromGhl("Anita Rao", "anita@example.test", null, null, null, null, null, null, null);

		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(call -> call.getArgument(0));
		given(contacts.findById(CONTACT_ID)).willReturn(Optional.of(contact));
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

	@Test
	void theClientSeesTheirOwnDraftAndNothingAboutTheExpert() {
		withAnAssignedExpert();

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID));

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

		String json = serialized(portal.clientView(tokenFor(BRAND, CASE_ID)));

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
						"approvalStatus", "awaitingAnswer");
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

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID));

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

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID));

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
		portal.clientView(tokenFor(BRAND, CASE_ID));
		Instant first = subject.getClientPortalReadAt();
		assertThat(first).isNotNull();

		portal.clientView(tokenFor(BRAND, CASE_ID));

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

		assertThatThrownBy(() -> portal.clientView(crossed)).isInstanceOf(ForbiddenException.class);
		verify(cases, never()).save(any(Case.class));
	}

	@Test
	void aTokenPointingAtANonexistentCaseIsRefused() {
		given(cases.findById(any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> portal.clientView(tokenFor(BRAND, CASE_ID)))
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
		given(checklistItems.findByCaseId(CASE_ID)).willReturn(java.util.List.of(outstanding));
		given(documents.findByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.CLIENT_UPLOAD))
				.willReturn(java.util.List.of(documentOn(CASE_ID, DocumentKind.CLIENT_UPLOAD,
						"passport.pdf", "brand/client/ghl-1/doc")));

		PortalCaseService.ClientDocumentsView view = portal.documents(tokenFor(BRAND, CASE_ID));

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

		String url = portal.documentUrl(tokenFor(BRAND, CASE_ID), own.getId());

		assertThat(url).isEqualTo("https://s3.example/presigned");
		verify(audit).recordPortalEvent(eq(BRAND), eq(PortalAudience.CLIENT), eq("CASE_DOCUMENT"),
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

			assertThatThrownBy(() -> portal.documentUrl(tokenFor(BRAND, CASE_ID), notTheirs.getId()))
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

		assertThatThrownBy(() -> portal.documentUrl(tokenFor(BRAND, CASE_ID), elsewhere.getId()))
				.isInstanceOf(ForbiddenException.class);
		verify(store, never()).presignedUrl(any());
	}

	/** Both writes go through Unit 04, on the case the token authorized — never on an id. */
	@Test
	void theTwoActionsDelegateToTheStateMachine() {
		given(lifecycle.clientApproveDraftFromPortal(subject)).willReturn(subject);
		given(lifecycle.clientRequestRevisionsFromPortal(subject, "soften the conclusion")).willReturn(subject);

		portal.approve(tokenFor(BRAND, CASE_ID));
		portal.requestRevisions(tokenFor(BRAND, CASE_ID), "soften the conclusion");

		verify(lifecycle).clientApproveDraftFromPortal(subject);
		verify(lifecycle).clientRequestRevisionsFromPortal(subject, "soften the conclusion");
	}
}
