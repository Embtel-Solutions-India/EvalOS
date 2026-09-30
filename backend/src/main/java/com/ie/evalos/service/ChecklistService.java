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
 * <p><strong>The chase sends nothing itself.</strong> It writes an audit row and publishes
 * {@code checklist.chased}; {@link CaseMailListener} emails the client after commit (D58,
 * invariant 14).
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
	 *
	 * <p>{@code lastChasedAt} is here for the same reason it is on {@link BoardRow}, and
	 * because the panel is the screen that changes it: without it the client had to stamp its
	 * own clock after a chase, which is a second copy of a fact the trail already holds and
	 * showed the browser's time rather than the recorded one.
	 */
	public record CaseChecklist(Case subject, List<DocumentChecklistItem> items, Instant lastChasedAt,
			Map<UUID, String> senders) {

		/** Unit 61: items the PC/CM has not sent to the client yet. */
		public int unsent() {
			return (int) items.stream().filter(item -> !item.isSent()).count();
		}

		/** The most recent send, for "already sent by … on …". */
		public java.util.Optional<DocumentChecklistItem> lastSent() {
			return items.stream().filter(DocumentChecklistItem::isSent)
					.max(Comparator.comparing(DocumentChecklistItem::getSentAt));
		}

		/** A sender's display name; null for a pre-D60 send, which has no sender. */
		public String senderOf(DocumentChecklistItem item) {
			return item.getSentBy() == null ? null : senders.get(item.getSentBy());
		}

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
	/** Unit 61: senders' display names for "sent by". */
	private final com.ie.evalos.repository.TeamMemberRepository teamMembers;

	ChecklistService(CaseLifecycleService lifecycle, CaseBoardService board,
			DocumentChecklistItemRepository checklistItems, AuditEventRepository auditEvents, AuditService audit,
			ApplicationEventPublisher events, com.ie.evalos.repository.TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
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
		List<CaseBoardService.BoardRow> visible = board.forCaller(null, brandId);
		// Unit 61: also any case with something still to send. An expert's evidence request lands
		// after document collection, and a board that hid it would leave that item unsendable.
		java.util.Set<UUID> needsSend = visible.isEmpty() ? java.util.Set.of()
				: checklistItems.caseIdsWithUnsent(
						visible.stream().map(row -> row.subject().getBrandId()).distinct().toList());
		List<CaseBoardService.BoardRow> waiting = visible.stream()
				.filter(row -> row.subject().getCurrentStage() == Stage.DOC_COLLECTION
						|| needsSend.contains(row.subject().getId()))
				.toList();

		List<UUID> caseIds = waiting.stream().map(row -> row.subject().getId()).toList();
		if (caseIds.isEmpty()) {
			return List.of();
		}
		// The brands these cases actually belong to, not the brandId argument: that one is
		// null for the GM, the only cross-brand reader, and a null would scope nothing.
		// Taking it off the rows keeps the batch reads narrowed to exactly what the scoped
		// read above already granted.
		List<UUID> brandIds = waiting.stream().map(row -> row.subject().getBrandId()).distinct().toList();

		Map<UUID, List<DocumentChecklistItem>> byCase = checklistItems
				.findByBrandIdInAndCaseIdIn(brandIds, caseIds).stream()
				.collect(Collectors.groupingBy(DocumentChecklistItem::getCaseId));
		Map<UUID, Instant> chased = lastChased(caseIds, brandIds);

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
	private Map<UUID, Instant> lastChased(List<UUID> caseIds, List<UUID> brandIds) {
		return auditEvents.findCaseActionScoped(OBJECT_TYPE, AuditAction.CHASED, caseIds, brandIds).stream()
				.collect(Collectors.toMap(AuditEvent::getObjectId, AuditEvent::getCreatedAt,
						BinaryOperator.maxBy(Comparator.naturalOrder())));
	}

	// --- one case's items ----------------------------------------------------

	@Transactional(readOnly = true)
	public CaseChecklist forCase(UUID caseId) {
		Case subject = lifecycle.read(caseId);
		List<DocumentChecklistItem> items = checklistItems.findByCaseId(subject.getId());
		java.util.Set<UUID> senderIds = items.stream().map(DocumentChecklistItem::getSentBy)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> senders = senderIds.isEmpty() ? Map.of()
				: teamMembers.findAllById(senderIds).stream()
						.collect(Collectors.toMap(com.ie.evalos.domain.TeamMember::getId,
								com.ie.evalos.domain.TeamMember::getDisplayName));
		return new CaseChecklist(subject, items,
				lastChased(List.of(subject.getId()), List.of(subject.getBrandId())).get(subject.getId()),
				senders);
	}

	/**
	 * Sends the case's unsent items to the client (Unit 61, D60): the PC's or the CM's one act.
	 *
	 * <p><strong>Only what is unsent, and refused when there is none.</strong> That is how a list
	 * is never sent twice: whichever of the two presses Send first publishes it, and the other then
	 * sees "already sent". Items added afterwards wait for the next Send.
	 *
	 * <p>Not stage-guarded, unlike the chase: an expert's evidence request adds an item after
	 * document collection, and it has to be sendable there. Publishes {@code CHECKLIST_REQUESTED}
	 * inside the transaction, which D58's checklist email will listen to.
	 */
	@Transactional
	public void send(UUID caseId) {
		Case subject = lifecycle.read(caseId);
		List<DocumentChecklistItem> unsent = checklistItems.findByCaseId(subject.getId()).stream()
				.filter(item -> !item.isSent())
				.toList();
		if (unsent.isEmpty()) {
			throw new IllegalTransitionException("the checklist has already been sent; add a document first");
		}
		UUID by = TenantContext.current().memberId();
		Instant now = Instant.now();
		unsent.forEach(item -> item.markSent(by, now));
		checklistItems.saveAll(unsent);

		record(subject, AuditAction.UPDATED, "Checklist sent to the client: " + unsent.stream()
				.map(DocumentChecklistItem::getLabel).collect(Collectors.joining(", ")));
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_REQUESTED, subject));
	}

	/**
	 * Sets one item's status.
	 *
	 * <p>The audit row is written against the <em>case</em>, not the item, so the Coordinator's
	 * work appears on the case timeline where the rest of the case's history is — the trail is
	 * only useful if one screen shows all of it. Nothing about the case row changes, so the
	 * before and after snapshots are identical except for the note, which states the change:
	 * that is what {@code CaseSnapshot.note} is for.
	 *
	 * <p>Returns nothing. Every caller answers the whole refreshed checklist through
	 * {@code forCase}, so a saved row handed back here was only ever discarded.
	 */
	@Transactional
	public void setStatus(UUID caseId, UUID itemId, ChecklistItemStatus status) {
		Case subject = lifecycle.read(caseId);
		DocumentChecklistItem item = itemOn(subject, itemId);
		ChecklistItemStatus before = item.getStatus();

		item.markStatus(status);
		checklistItems.save(item);
		record(subject, AuditAction.UPDATED, "%s: %s → %s".formatted(item.getLabel(), before, status));
	}

	/**
	 * Adds a document the template did not know about. It opens <strong>unsent</strong> (Unit 61):
	 * the client sees it only after the next {@link #send}.
	 *
	 * <p>Opens as {@code REQUIRED}, which is what makes the case incomplete again — no extra
	 * rule is needed for that, because {@code markDocsComplete} already refuses a case with any
	 * item that is not uploaded or approved. Inside {@code DOC_COLLECTION} that makes adding an
	 * item the way to reopen a case whose documents turned out to be short; past that stage the
	 * case has already left the guard behind, and adding one records the requirement on the
	 * trail without pulling the case back. Neither write is stage-guarded — only the chase is,
	 * because only the chase reaches the client.
	 */
	@Transactional
	public void addItem(UUID caseId, String label) {
		Case subject = lifecycle.read(caseId);
		checklistItems.save(new DocumentChecklistItem(
				subject.getBrandId(), subject.getId(), label, ChecklistItemStatus.REQUIRED));

		record(subject, AuditAction.CREATED, "Required document added: " + label);
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
		// CaseMailListener emails the reminder after commit (D58). Published inside this
		// transaction, so a rolled-back chase cannot leave an event claiming the client was contacted.
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_CHASED, subject));
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
