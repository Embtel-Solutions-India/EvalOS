package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ApplicationDocument;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientApplicationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Unit 53 §4: the request's documents follow it onto the case, and the case survives them failing.
 *
 * <p>The two that matter are {@link #theSameObjectKeyIsReused()} — which is the whole reason §1
 * keyed by the contact rather than the case — and {@link #aReplayedWonEventCarriesNothingTwice()},
 * because {@code opportunity.won} is the only door into a case (invariant 8) and GHL may knock
 * more than once.
 */
class RequestDocumentCarryForwardTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final String GHL_OPPORTUNITY = "opp-4711";

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ClientApplicationRepository applications = mock(ClientApplicationRepository.class);
	private final ApplicationDocumentService requestDocuments = mock(ApplicationDocumentService.class);
	private final CaseDocumentRepository caseDocuments = mock(CaseDocumentRepository.class);
	private final AuditService audit = mock(AuditService.class);

	private final RequestDocumentCarryForward carryForward = new RequestDocumentCarryForward(cases,
			applications, requestDocuments, caseDocuments, audit);

	private Case subject;
	private ClientApplication request;

	@BeforeEach
	void aWonDealWithARequestBehindIt() {
		subject = mock(Case.class);
		given(subject.getId()).willReturn(UUID.randomUUID());
		given(subject.getBrandId()).willReturn(BRAND);
		given(subject.getGhlOpportunityId()).willReturn(GHL_OPPORTUNITY);
		given(cases.findById(any())).willReturn(Optional.of(subject));

		request = new ClientApplication(BRAND, UUID.randomUUID(), "academic_evaluation",
				"Academic Evaluation", null);
		ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
		given(applications.findByBrandIdAndGhlOpportunityId(BRAND, GHL_OPPORTUNITY))
				.willReturn(Optional.of(request));

		given(caseDocuments.findByCaseIdAndKindOrderByVersionDesc(any(), any())).willReturn(List.of());
		given(caseDocuments.saveAndFlush(any())).willAnswer((call) -> {
			CaseDocument row = call.getArgument(0);
			ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
			return row;
		});
	}

	private ApplicationDocument document(String filename, String key) {
		ApplicationDocument row = new ApplicationDocument(BRAND, request.getId(), UUID.randomUUID(),
				key, filename, "application/pdf", 1024L);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private void wonEvent() {
		carryForward.on(new CaseEvents.CaseEvent(CaseEvents.Type.CASE_CREATED, BRAND, subject.getId(),
				UUID.randomUUID(), null, null));
	}

	/**
	 * <strong>The object is not copied and not re-keyed.</strong>
	 *
	 * <p>This is §1's decision paying off: the key is the contact's prefix, so the case row points
	 * at the object the client already uploaded. A case-scoped key would have made this a copy in
	 * S3, and a copy is a second thing to keep in step for the life of the case.
	 */
	@Test
	void theSameObjectKeyIsReused() {
		ApplicationDocument sent = document("transcript.pdf", "brand/client/ghl-c-1/doc-1");
		given(requestDocuments.uncarried(request.getId())).willReturn(List.of(sent));

		wonEvent();

		org.mockito.ArgumentCaptor<CaseDocument> written =
				org.mockito.ArgumentCaptor.forClass(CaseDocument.class);
		then(caseDocuments).should().saveAndFlush(written.capture());
		assertThat(written.getValue().getObjectKey()).isEqualTo("brand/client/ghl-c-1/doc-1");
		assertThat(written.getValue().getFilename()).isEqualTo("transcript.pdf");
		assertThat(written.getValue().getKind()).isEqualTo(DocumentKind.CLIENT_UPLOAD);
		then(requestDocuments).should().markCarried(eq(sent.getId()), any());
	}

	/**
	 * <strong>A replay carries nothing twice.</strong>
	 *
	 * <p>The idempotency is not a flag this class checks — it is the absence of uncarried rows,
	 * because {@code carried_to_case_document_id} was stamped on the first pass. A second delivery
	 * therefore finds an empty list and writes no {@code case_document} at all.
	 */
	@Test
	void aReplayedWonEventCarriesNothingTwice() {
		given(requestDocuments.uncarried(request.getId())).willReturn(List.of());

		wonEvent();

		then(caseDocuments).should(never()).saveAndFlush(any());
	}

	/**
	 * <strong>Two documents in one pass get two versions.</strong>
	 *
	 * <p>{@code uq_case_document_version} is {@code (case, kind, version)}, so a pass that stamped
	 * them both version 1 would violate it and take the whole carry-forward down with it. The
	 * version is read per document and {@code saveAndFlush} is what makes the second read see the
	 * first write.
	 */
	@Test
	void twoDocumentsInOnePassDoNotCollideOnTheVersion() {
		ApplicationDocument first = document("transcript.pdf", "key-1");
		ApplicationDocument second = document("degree.pdf", "key-2");
		given(requestDocuments.uncarried(request.getId())).willReturn(List.of(first, second));

		CaseDocument alreadyThere = mock(CaseDocument.class);
		given(alreadyThere.getVersion()).willReturn(1);
		given(caseDocuments.findByCaseIdAndKindOrderByVersionDesc(any(), any()))
				.willReturn(List.of())
				.willReturn(List.of(alreadyThere));

		wonEvent();

		org.mockito.ArgumentCaptor<CaseDocument> written =
				org.mockito.ArgumentCaptor.forClass(CaseDocument.class);
		then(caseDocuments).should(org.mockito.Mockito.times(2)).saveAndFlush(written.capture());
		assertThat(written.getAllValues()).extracting(CaseDocument::getVersion)
				.containsExactly(1, 2);
	}

	/**
	 * <strong>The case survives a carry-forward that throws</strong> (§4).
	 *
	 * <p>{@code opportunity.won} is the only door into a case, so a failure here must not propagate:
	 * a case that exists with a document still on the request is recoverable by hand or by a
	 * redelivery, while a won deal that 500s leaves no case at all and no second chance.
	 */
	@Test
	void aFailureIsLoggedAndAuditedRatherThanFailingTheCase() {
		given(requestDocuments.uncarried(request.getId()))
				.willThrow(new IllegalStateException("S3 is unreachable"));

		wonEvent();

		then(audit).should().recordSystemEvent(eq(BRAND), eq("CASE"), any(), any(), any(), any());
	}

	/** A case opened by hand has no request behind it, which is most of them. */
	@Test
	void aCaseWithNoRequestBehindItIsLeftAlone() {
		given(subject.getGhlOpportunityId()).willReturn(null);

		wonEvent();

		then(applications).shouldHaveNoInteractions();
		then(caseDocuments).should(never()).saveAndFlush(any());
	}
}
