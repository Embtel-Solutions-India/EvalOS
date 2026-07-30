package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles one case with the three things the detail page needs that the case row does not
 * carry: who the client is, who the expert is, and how the document checklist stands.
 *
 * <p>The case itself comes from {@link CaseLifecycleService#read}, so scope and the 403 for
 * an out-of-scope case are decided in exactly one place — this service never builds its own
 * predicate and therefore cannot disagree with the rest of the system about what the caller
 * may see. Everything else is looked up by an id that came off a case the caller already
 * proved they could read.
 */
@Service
public class CaseDetailService {

	/** How the checklist stands, for the summary chip. The board itself is Unit 10. */
	public record ChecklistSummary(int total, int complete) {
	}

	/** One case plus the joined reads the detail page draws. */
	public record CaseWithContext(
			Case subject,
			String clientName,
			String expertName,
			String expertTier,
			ChecklistSummary checklist) {
	}

	private final CaseLifecycleService lifecycle;
	private final ContactSnapshotRepository contacts;
	private final ExpertRepository experts;
	private final DocumentChecklistItemRepository checklistItems;

	CaseDetailService(CaseLifecycleService lifecycle, ContactSnapshotRepository contacts, ExpertRepository experts,
			DocumentChecklistItemRepository checklistItems) {
		this.lifecycle = lifecycle;
		this.contacts = contacts;
		this.experts = experts;
		this.checklistItems = checklistItems;
	}

	@Transactional(readOnly = true)
	public CaseWithContext detail(UUID caseId) {
		Case subject = lifecycle.read(caseId);

		// Scoped, not the inherited findById: ScopedRepository's own javadoc calls a scoped read
		// that skips findScoped a defect, and ContactSnapshotRepository grants no carve-out for
		// findById the way the checklist finder does for itself. The contact id does come off an
		// already-scoped case, so nothing was reachable through it — but the contact FK is
		// `contact_id REFERENCES contact_snapshot(id)` with no brand in the key, so the safety was
		// resting on provenance rather than on the query. Both axes are brand-only for every role
		// (a Self caller with no assignment column is deliberately not narrowed), so this reads
		// the same rows for everyone who could already open the case.
		TenantContext caller = TenantContext.current();
		String clientName = Optional.ofNullable(subject.getContactId())
				.flatMap(id -> contacts.findScoped(caller, id))
				.map(ContactSnapshot::getFullName)
				.orElse(null);

		Optional<Expert> expert = Optional.ofNullable(subject.getExpertId())
				.flatMap(id -> experts.findScoped(caller, id));

		List<DocumentChecklistItem> items = checklistItems.findByCaseId(subject.getId());
		ChecklistSummary checklist = new ChecklistSummary(items.size(),
				(int) items.stream().filter(item -> item.getStatus().isComplete()).count());

		return new CaseWithContext(subject, clientName,
				expert.map(Expert::getFullName).orElse(null),
				expert.map(Expert::getTier).map(Enum::name).orElse(null),
				checklist);
	}
}
