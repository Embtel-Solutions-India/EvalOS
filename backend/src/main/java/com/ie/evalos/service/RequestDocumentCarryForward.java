package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.ApplicationDocument;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientApplicationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The request's documents follow it onto the case — Unit 53 §4.
 *
 * <p><strong>Nothing is copied in S3 and nothing is re-keyed.</strong> The object was written under
 * {@code DocumentStore.clientKey(brand, ghlContactId, …)} — the <em>contact's</em> prefix, never the
 * case's — so carrying a document forward is one new row pointing at the same {@code object_key}.
 * That is the whole payoff of §1's decision to key by the person: a case-scoped key would have made
 * this a copy, and a copy is a second thing to keep in step.
 *
 * <p><strong>A listener rather than a call inside {@code CaseIntakeService}, and the spec's own
 * requirement is why.</strong> §4 says this "never fails the case" — {@code opportunity.won} is the
 * only door into a case (invariant 8), so a carry-forward that threw would turn a recoverable
 * problem (a case exists, a document is still on the request) into an unrecoverable one (the won
 * deal 500s and the case never exists). Hanging off {@code CASE_CREATED}, which intake already
 * publishes, gets that isolation structurally instead of by remembering a try/catch — and keeps
 * three more collaborators off a class {@code DomainInvariantsTest} already watches.
 *
 * <p><strong>Idempotent through {@code carried_to_case_document_id}.</strong> GHL may deliver the
 * same won event twice; the second pass finds nothing uncarried and does nothing. The stamp is
 * write-once in the entity, so even a concurrent double-delivery cannot produce two
 * {@code case_document} rows per request document.
 */
@Component
public class RequestDocumentCarryForward {

	private static final Logger log = LoggerFactory.getLogger(RequestDocumentCarryForward.class);

	private final CaseRepository cases;
	private final ClientApplicationRepository applications;
	private final ApplicationDocumentService requestDocuments;
	private final CaseDocumentRepository caseDocuments;
	private final AuditService audit;

	RequestDocumentCarryForward(CaseRepository cases, ClientApplicationRepository applications,
			ApplicationDocumentService requestDocuments, CaseDocumentRepository caseDocuments,
			AuditService audit) {
		this.cases = cases;
		this.applications = applications;
		this.requestDocuments = requestDocuments;
		this.caseDocuments = caseDocuments;
		this.audit = audit;
	}

	@EventListener
	@Transactional
	public void on(CaseEvents.CaseEvent event) {
		if (event.type() != CaseEvents.Type.CASE_CREATED) {
			return;
		}
		try {
			carry(event.caseId());
		}
		catch (RuntimeException failure) {
			// **Logged and audited, never rethrown** (§4). The case is the thing that must exist;
			// a document still sitting on the request is visible in the trail and can be carried
			// by hand or by a redelivery of the same webhook.
			log.error("Could not carry request documents onto case {}; the case stands and the "
					+ "documents remain on the request", event.caseId(), failure);
			audit.recordSystemEvent(event.brandId(), "CASE", event.caseId(), AuditAction.FLAGGED,
					null, "request documents could not be carried forward: " + failure);
		}
	}

	private void carry(UUID caseId) {
		Optional<Case> found = cases.findById(caseId);
		if (found.isEmpty()) {
			return;
		}
		Case subject = found.get();
		String ghlOpportunityId = subject.getGhlOpportunityId();
		if (ghlOpportunityId == null || ghlOpportunityId.isBlank()) {
			// A case opened by hand has no request behind it, which is most of them today.
			return;
		}

		Optional<ClientApplication> request = applications
				.findByBrandIdAndGhlOpportunityId(subject.getBrandId(), ghlOpportunityId);
		if (request.isEmpty()) {
			return;
		}

		int carried = 0;
		for (ApplicationDocument document : requestDocuments.uncarried(request.get().getId())) {
			CaseDocument onTheCase = new CaseDocument(subject.getBrandId(), subject.getId(),
					DocumentKind.CLIENT_UPLOAD, nextVersion(subject.getId()), null, ActorType.CLIENT,
					// The note says where it came from, because on the case it is indistinguishable
					// from something the client uploaded later — and "when did we get this" is the
					// question a Coordinator actually asks.
					"sent with the request");
			onTheCase.setObjectKey(document.getObjectKey());
			onTheCase.setFilename(document.getFilename());
			CaseDocument saved = caseDocuments.saveAndFlush(onTheCase);

			requestDocuments.markCarried(document.getId(), saved.getId());
			carried++;
		}

		if (carried > 0) {
			log.info("Carried {} request document(s) onto case {}", carried, subject.getId());
			audit.recordSystemEvent(subject.getBrandId(), "CASE", subject.getId(),
					AuditAction.UPDATED, null,
					carried + " document(s) sent with the request carried onto the case");
		}
	}

	/**
	 * The next {@code CLIENT_UPLOAD} version on this case.
	 *
	 * <p>Read per document rather than once, because {@code uq_case_document_version} is
	 * {@code (case, kind, version)} and two documents carried in one pass would otherwise collide
	 * on the same number. {@code saveAndFlush} above is what makes the next read see the last write.
	 */
	private int nextVersion(UUID caseId) {
		return caseDocuments.findByCaseIdAndKindOrderByVersionDesc(caseId, DocumentKind.CLIENT_UPLOAD)
				.stream().findFirst().map((latest) -> latest.getVersion() + 1).orElse(1);
	}
}
