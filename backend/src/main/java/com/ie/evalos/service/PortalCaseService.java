package com.ie.evalos.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a client may see of their own case, and the two things they may do to it.
 *
 * <p><strong>A narrow read of its own, not a widened {@code CaseDetailService}.</strong> That DTO
 * carries the deal value, the PM's strategy notes, the expert's identity, the assignment slots and
 * the audit timeline — every one of which is somebody else's information. This projects a
 * <strong>whitelist</strong> instead, for the same reason Unit 13's redaction is one: a field added
 * to {@code Case} in a later unit does not appear here, because the person adding it does not have
 * to remember a rule in a file they are not editing.
 *
 * <p><strong>The token is the scope.</strong> Both reads below take the case id off
 * {@link PortalPrincipal}, which came off the token's own row — so there is no case parameter
 * anywhere on this surface and nothing to enumerate. {@code ScopePredicate} is not involved and no
 * synthetic {@code TenantContext} is manufactured: a client is not a staff user with a narrower
 * tier.
 */
@Service
public class PortalCaseService {

	/**
	 * The whole client-facing view of a case.
	 *
	 * <p>Deliberately absent, each because it belongs to somebody else: {@code deal_value},
	 * {@code pm_strategy_notes}, the expert's name and institution, {@code invoice_ref},
	 * {@code campaign_attribution}, every assignment field, the audit timeline, the document
	 * checklist, and <strong>{@code drive_link}</strong> — which is the client's own document
	 * folder and is never sent here, not renamed, not aliased and not defaulted to when
	 * {@code draftLink} is missing.
	 *
	 * @param caseReference   the human-facing case code, so client and staff quote the same thing
	 * @param draftLink       null until a draft has been submitted, which the portal reads as
	 *                        "not ready" rather than substituting another link
	 * @param awaitingAnswer  whether the two actions are live. Stated by the server rather than
	 *                        derived from {@code approvalStatus} in the client, because the
	 *                        transition's own guard is the authority on it
	 * <p><strong>The client is told nothing about the expert (Unit 13 removed, 2026-09-02).</strong>
	 * This payload used to carry a redacted profile — credentials with the name, institution and
	 * contact details stripped. Withholding the expert's identity entirely is the stronger position
	 * and it is now the only one: there is no redaction to get wrong, and no generated document to
	 * keep anonymous.
	 */
	public record ClientDraftView(
			String clientName,
			ServiceType serviceType,
			String caseReference,
			String draftLink,
			int draftVersion,
			ClientApprovalStatus approvalStatus,
			boolean awaitingAnswer) {
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final ExpertRepository experts;
	private final CaseLifecycleService lifecycle;

	PortalCaseService(CaseRepository cases, ContactSnapshotRepository contacts, ExpertRepository experts,
			CaseLifecycleService lifecycle) {
		this.cases = cases;
		this.contacts = contacts;
		this.experts = experts;
		this.lifecycle = lifecycle;
	}

	/**
	 * The client's one screen, and the read receipt.
	 *
	 * <p>{@code client_portal_read_at} is stamped <strong>once</strong>, on the first read: it
	 * answers "has the client seen this at all", which is what the Case Manager needs to know before
	 * chasing. "When did they last look" is {@code portal_access.last_seen_at}, which
	 * {@code PortalAccessService.resolve} moves on every request. Two fields because they are two
	 * questions; one that did both would answer neither.
	 *
	 * <p>Not {@code readOnly} for exactly that stamp.
	 */
	@Transactional
	public ClientDraftView clientView(PortalPrincipal principal) {
		Case subject = authorized(principal);

		if (subject.getClientPortalReadAt() == null) {
			subject.setClientPortalReadAt(Instant.now());
			cases.save(subject);
		}
		return view(subject);
	}

	/** The projection itself, shared with the two writes so they answer the page's new state. */
	private ClientDraftView view(Case subject) {
		// Both lookups are by an id that came off the authorized case, which is the same
		// provenance the batched staff finders rely on — and unlike them there is no
		// TenantContext here to scope with. Neither id ever arrives from a request.
		String clientName = Optional.ofNullable(subject.getContactId())
				.flatMap(contacts::findById)
				.map(ContactSnapshot::getFullName)
				.orElse(null);

		return new ClientDraftView(
				clientName,
				subject.getServiceType(),
				subject.getCaseCode(),
				subject.getDraftLink(),
				subject.getDraftVersionCount(),
				subject.getClientApprovalStatus(),
				subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING);
	}

	/**
	 * Handoff B. The guards, the stage change, the trail and the event are all Unit 04's.
	 *
	 * <p>Answers the page's new state, so the client's screen can say what happens next without a
	 * second round trip — and so what it shows afterwards comes from the case rather than from the
	 * client's assumption that the POST worked.
	 */
	@Transactional
	public ClientDraftView approve(PortalPrincipal principal) {
		return view(lifecycle.clientApproveDraftFromPortal(authorized(principal)));
	}

	/** Revisions carry the client's own words, which is what the Case Manager works from. */
	@Transactional
	public ClientDraftView requestRevisions(PortalPrincipal principal, String notes) {
		return view(lifecycle.clientRequestRevisionsFromPortal(authorized(principal), notes));
	}

	/**
	 * The case this token admits.
	 *
	 * <p>{@code findById} and not {@code findScoped}, which is the one deliberate exception to
	 * {@code ScopedRepository}'s rule, and the reason is that there is nothing to scope <em>by</em>:
	 * the id is not a parameter a caller supplied, it is the row the credential names. The brand
	 * check below is what makes brand isolation a real predicate on this surface rather than an
	 * argument from provenance — it cannot currently fail, because {@code brand_id} is
	 * {@code updatable = false} on both rows, and it is one line that would catch it if that ever
	 * stopped being true.
	 */
	private Case authorized(PortalPrincipal principal) {
		UUID caseId = principal.caseId();
		Case subject = cases.findById(caseId)
				.orElseThrow(() -> new ForbiddenException("This link no longer points at a case"));
		if (!subject.getBrandId().equals(principal.brandId())) {
			throw new ForbiddenException("This link no longer points at a case");
		}
		return subject;
	}
}
