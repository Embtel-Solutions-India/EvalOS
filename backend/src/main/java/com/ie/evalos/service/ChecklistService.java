package com.ie.evalos.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseLifecycleService.CaseSnapshot;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Coordinator's stage: what the client still owes, and the one lever for asking
 * them again.
 *
 * <p>Nothing here moves a case. {@code docs-complete} is Unit 04's transition and stays
 * there — this service maintains the rows that transition reads, so the guard and the board
 * can never disagree about whether a case is ready. Every read starts from
 * {@link CaseLifecycleService#read}, so scope is decided in the one place the rest of the
 * system decides it and an out-of-scope case is refused before a single item is fetched.
 *
 * <p><strong>The chase sends nothing.</strong> It writes an audit row and publishes
 * {@code checklist.reminder}; GHL delivers the message to the client (invariant 14). EvalOS
 * has no mail server and this is not the place one would go.
 */
@Service
public class ChecklistService {

	private static final String OBJECT_TYPE = "CASE";

	/**
	 * One case awaiting documents.
	 *
	 * <p>{@code lastChasedAt} is read back out of the append-only trail rather than kept in a
	 * column: the chase had to be recorded there regardless, and a second copy of one fact is
	 * a second thing that can drift from the first. It also means Unit 19's timers inherit the
	 * answer without a migration.
	 */
	public record BoardRow(Case subject, String clientName, int total, int complete, Instant lastChasedAt) {

		/** What the completeness bar and the "ready" state both read. */
		public boolean satisfied() {
			return ChecklistService.satisfied(total, complete);
		}
	}

	/**
	 * One case's checklist, with the case it hangs off.
	 *
	 * <p>The case comes back too because the panel needs its Drive link — the documents live
	 * in Google Drive and EvalOS holds the link and never the bytes (invariant 14), so the
	 * link is the only route to the actual files and a checklist without it is a list of
	 * things you cannot go and look at.
	 */
	public record CaseChecklist(Case subject, List<DocumentChecklistItem> items) {

		public int complete() {
			return (int) items.stream().filter(item -> item.getStatus().isComplete()).count();
		}

		public boolean satisfied() {
			return ChecklistService.satisfied(items.size(), complete());
		}
	}

	/**
	 * Whether every document is in — the checklist half of {@code markDocsComplete}'s guard,
	 * and the only half this unit owns.
	 *
	 * <p>An empty checklist is <em>not</em> satisfied, which matches the transition: it
	 * refuses a case with no items at all rather than treating "nothing required" as "nothing
	 * outstanding". A template that produced no rows is a bug, not a fast track.
	 */
	static boolean satisfied(int total, int complete) {
		return total > 0 && complete == total;
	}

	private final CaseLifecycleService lifecycle;
	private final CaseBoardService board;
	private final DocumentChecklistItemRepository checklistItems;
	private final AuditEventRepository auditEvents;
	private final AuditService audit;
	private final ApplicationEventPublisher events;

	ChecklistService(CaseLifecycleService lifecycle, CaseBoardService board,
			DocumentChecklistItemRepository checklistItems, AuditEventRepository auditEvents, AuditService audit,
			ApplicationEventPublisher events) {
		this.lifecycle = lifecycle;
		this.board = board;
		this.checklistItems = checklistItems;
		this.auditEvents = auditEvents;
		this.audit = audit;
		this.events = events;
	}

	// --- the board -----------------------------------------------------------

	/**
	 * Every case this caller can see that is still collecting documents, most urgent first.
	 *
	 * <p>Built on {@link CaseBoardService#forCaller} rather than a second scoped query, for
	 * the reason that service gives for building on {@code CaseLifecycleService.list}: a
	 * screen that filtered its own way could disagree with every other read about what the
	 * caller may see. It inherits the SLA recompute, the batched client names, and the rule
	 * that {@code brandId} can only ever narrow.
	 *
	 * <p>A case holding an exception state is still listed. On the production board a held
	 * case leaves its column because nobody is working it; here the opposite is true — "on
	 * hold awaiting client" is precisely the case whose documents have not arrived, and
	 * dropping it would hide the queue this screen exists to show.
	 */
	@Transactional(readOnly = true)
	public List<BoardRow> board(UUID brandId) {
		List<CaseBoardService.BoardRow> waiting = board.forCaller(null, brandId).stream()
				.filter(row -> row.subject().getCurrentStage() == Stage.DOC_COLLECTION)
				.toList();

		List<UUID> caseIds = waiting.stream().map(row -> row.subject().getId()).toList();
		if (caseIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<DocumentChecklistItem>> byCase = checklistItems.findByCaseIdIn(caseIds).stream()
				.collect(Collectors.groupingBy(DocumentChecklistItem::getCaseId));
		Map<UUID, Instant> chased = lastChased(caseIds);

		return waiting.stream()
				.map(row -> row(row, byCase.getOrDefault(row.subject().getId(), List.of()),
						chased.get(row.subject().getId())))
				.sorted(BY_URGENCY)
				.toList();
	}

	private static BoardRow row(CaseBoardService.BoardRow row, List<DocumentChecklistItem> items, Instant chased) {
		return new BoardRow(row.subject(), row.clientName(), items.size(),
				(int) items.stream().filter(item -> item.getStatus().isComplete()).count(), chased);
	}

	/**
	 * Oldest wait first, which is what "sort by urgency" means on this screen.
	 *
	 * <p>Not the deadline, and not the SLA status: every case in this column shares one
	 * budget, so the deadline mostly re-sorts by service type, and the RAG status has three
	 * values and would leave the whole red band in arbitrary order. Time in the stage is the
	 * thing the Coordinator is actually triaging. A case with no stamp sorts last rather
	 * than first — it has no wait to be at the top of.
	 */
	private static final Comparator<BoardRow> BY_URGENCY = Comparator.comparing(
			row -> row.subject().getStageEnteredAt(),
			Comparator.nullsLast(Comparator.naturalOrder()));

	/** The most recent chase per case, in one query rather than one per row. */
	private Map<UUID, Instant> lastChased(List<UUID> caseIds) {
		return auditEvents.findByObjectTypeAndActionAndObjectIdIn(OBJECT_TYPE, AuditAction.CHASED, caseIds).stream()
				.collect(Collectors.toMap(AuditEvent::getObjectId, AuditEvent::getCreatedAt,
						BinaryOperator.maxBy(Comparator.naturalOrder())));
	}

	// --- one case's items ----------------------------------------------------

	@Transactional(readOnly = true)
	public CaseChecklist forCase(UUID caseId) {
		Case subject = lifecycle.read(caseId);
		return new CaseChecklist(subject, checklistItems.findByCaseId(subject.getId()));
	}

	/**
	 * Sets one item's status.
	 *
	 * <p>The audit row is written against the <em>case</em>, not the item, so the Coordinator's
	 * work appears on the case timeline where the rest of the case's history is — the trail is
	 * only useful if one screen shows all of it. Nothing about the case row changes, so the
	 * before and after snapshots are identical except for the note, which states the change:
	 * that is what {@code CaseSnapshot.note} is for.
	 */
	@Transactional
	public DocumentChecklistItem setStatus(UUID caseId, UUID itemId, ChecklistItemStatus status) {
		Case subject = lifecycle.read(caseId);
		DocumentChecklistItem item = itemOn(subject, itemId);
		ChecklistItemStatus before = item.getStatus();

		item.markStatus(status);
		DocumentChecklistItem saved = checklistItems.save(item);
		record(subject, AuditAction.UPDATED, "%s: %s → %s".formatted(item.getLabel(), before, status));
		return saved;
	}

	/**
	 * Adds a document the template did not know about.
	 *
	 * <p>Opens as {@code REQUIRED}, which is what makes the case incomplete again — no extra
	 * rule is needed for that, because {@code markDocsComplete} already refuses a case with any
	 * item that is not uploaded or approved. Adding one is therefore the way to reopen a case
	 * whose documents turned out to be short.
	 */
	@Transactional
	public DocumentChecklistItem addItem(UUID caseId, String label) {
		Case subject = lifecycle.read(caseId);
		DocumentChecklistItem item = checklistItems.save(new DocumentChecklistItem(
				subject.getBrandId(), subject.getId(), label, ChecklistItemStatus.REQUIRED));

		record(subject, AuditAction.CREATED, "Required document added: " + label);
		return item;
	}

	/**
	 * Asks the client again for what is outstanding.
	 *
	 * <p>Refused outside {@code DOC_COLLECTION}. Not a formality: this reaches a real client
	 * through GHL, and "please send your documents" to somebody whose case is already with the
	 * expert is a mistake EvalOS would be making outwardly, not internally. There is no
	 * cool-off between chases — a Coordinator who sends two is answering a phone call, and the
	 * trail records both.
	 */
	@Transactional
	public void chase(UUID caseId) {
		Case subject = lifecycle.read(caseId);
		if (subject.getCurrentStage() != Stage.DOC_COLLECTION) {
			throw new IllegalTransitionException("the case is no longer collecting documents");
		}

		record(subject, AuditAction.CHASED, "Document chase sent to the client");
		// GHL delivers it. Published inside this transaction, so a rolled-back chase cannot
		// leave an event claiming the client was contacted.
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_REMINDER, subject));
	}

	// --- shared plumbing -----------------------------------------------------

	/**
	 * The item, proved to be on this case and in this brand.
	 *
	 * <p>Two guards rather than one because they cover different failures: {@code findScoped}
	 * keeps another brand's row out even though the id came from a request, and the case check
	 * keeps a caller from editing a different case's item within their own brand. Both answer
	 * the same 403, so the two cases are indistinguishable from outside.
	 */
	private DocumentChecklistItem itemOn(Case subject, UUID itemId) {
		return checklistItems.findScoped(TenantContext.current(), itemId)
				.filter(item -> subject.getId().equals(item.getCaseId()))
				.orElseThrow(() -> new ForbiddenException("No checklist item " + itemId + " on this case"));
	}

	private void record(Case subject, AuditAction action, String note) {
		audit.recordEvent(OBJECT_TYPE, subject.getId(), action, TenantContext.current().memberId(),
				CaseSnapshot.of(subject), CaseSnapshot.of(subject, note));
	}
}
