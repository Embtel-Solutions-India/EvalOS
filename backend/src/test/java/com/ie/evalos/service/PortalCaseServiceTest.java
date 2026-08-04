package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final RedactedProfileService profiles = mock(RedactedProfileService.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final PortalCaseService portal = new PortalCaseService(
			cases, contacts, experts, profiles, lifecycle);

	private Case subject;

	private static PortalPrincipal tokenFor(UUID brandId, UUID caseId) {
		return new PortalPrincipal(UUID.randomUUID(), brandId, caseId, PortalAudience.CLIENT);
	}

	@BeforeEach
	void aCaseWithADraftWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_GENERATION);
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
		subject.setDriveLink("https://drive.google.com/drive/folders/client-documents");
		subject.setAssignedPm(UUID.randomUUID());
		subject.setAssignedCm(UUID.randomUUID());
		subject.setAssignedCoordinator(UUID.randomUUID());

		ContactSnapshot contact = new ContactSnapshot(BRAND, "ghl-1");
		contact.syncFromGhl("Anita Rao", "anita@example.test", null, null, null, null, null, null, null);

		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(call -> call.getArgument(0));
		given(contacts.findById(CONTACT_ID)).willReturn(Optional.of(contact));
	}

	private void withAnAssignedExpert() {
		UUID expertId = UUID.randomUUID();
		subject.setExpertId(expertId);
		Expert expert = new Expert(BRAND, "Dr Ada Lovelace");
		given(experts.findById(expertId)).willReturn(Optional.of(expert));
		given(profiles.redactedFor(subject, expert)).willReturn(
				new RedactedProfileService.Profile("<html>credentials only</html>", "Expert AK"));
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
	void theClientSeesTheirOwnDraftAndTheAnonymousProfile() {
		withAnAssignedExpert();

		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID));

		assertThat(view.clientName()).isEqualTo("Anita Rao");
		assertThat(view.caseReference()).isEqualTo("IE-2026-0001");
		assertThat(view.serviceType()).isEqualTo(ServiceType.EXPERT_OPINION_LETTER);
		assertThat(view.draftLink()).isEqualTo("https://docs.google.com/document/d/draft/edit");
		assertThat(view.draftVersion()).isEqualTo(2);
		assertThat(view.approvalStatus()).isEqualTo(ClientApprovalStatus.PENDING);
		assertThat(view.awaitingAnswer()).isTrue();
		assertThat(view.expertReference()).isEqualTo("Expert AK");
		assertThat(view.expertProfile()).doesNotContain("Ada Lovelace");
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
						"approvalStatus", "awaitingAnswer", "expertProfile", "expertReference");
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

	/** No expert yet is a missing profile, not a failed read. */
	@Test
	void aCaseWithNoExpertHasNoProfile() {
		PortalCaseService.ClientDraftView view = portal.clientView(tokenFor(BRAND, CASE_ID));

		assertThat(view.expertProfile()).isNull();
		assertThat(view.expertReference()).isNull();
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
