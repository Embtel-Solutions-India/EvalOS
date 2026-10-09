package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.PmOverviewService.QueueRow;
import com.ie.evalos.service.PmOverviewService.StageCount;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the Coordinator still owes and holds: documents the clients owe, checklists not yet sent, who was
 * chased and when, and the cases sitting in the Coordinator's four stages.
 *
 * <p>The case set is {@link CoordinatorMetricsService#scoped(UUID)} — one scope read, no second
 * predicate. Items, the unsent set and chase events are then loaded in batch for those cases and their
 * brands. Read-only; nothing is stored. Counts say what they count: cases, or checklist items.
 */
@Service
public class CoordinatorWorkService {

	/** Rows returned per list; the worst come first and the cap drops the rest. */
	static final int ROW_CAP = 25;

	/** The four stages the Coordinator owns (STAGE_COLUMNS), in pipeline order. */
	static final List<Stage> COORDINATOR_STAGES = List.of(Stage.DOC_COLLECTION, Stage.READY_TO_SEND,
			Stage.CLIENT_REVIEW, Stage.READY_TO_DELIVER);

	private final CoordinatorMetricsService coordinator;
	private final DocumentChecklistItemRepository items;
	private final AuditEventRepository audit;
	private final TeamMemberQueryService members;
	private final DeadlineRiskCalculator deadlines;
	private final BusinessCalendar calendar;

	CoordinatorWorkService(CoordinatorMetricsService coordinator, DocumentChecklistItemRepository items,
			AuditEventRepository audit, TeamMemberQueryService members, DeadlineRiskCalculator deadlines,
			BusinessCalendar calendar) {
		this.coordinator = coordinator;
		this.items = items;
		this.audit = audit;
		this.members = members;
		this.deadlines = deadlines;
		this.calendar = calendar;
	}

	/** One case that still owes documents or an unsent checklist. Item counts are checklist items. */
	public record OwedRow(UUID caseId, String caseCode, String cmName, Long waitingBusinessHours,
			DeadlineRisk deadlineRisk, int total, int approved, int uploaded, int required, int missing,
			int incorrect, boolean unsent, Instant lastChasedAt) {
	}

	/** {@code awaitingVerification} and {@code blockerItems} count items; {@code unsentCases} counts cases. */
	public record Documents(int awaitingVerification, int blockerItems, int unsentCases, List<OwedRow> owed) {
	}

	public record CoordinatorWork(List<StageCount> stages, int blocked, Documents documents,
			List<QueueRow> clientReview, List<QueueRow> readyToDeliver) {
	}

	@Transactional(readOnly = true)
	public CoordinatorWork forCaller(UUID brandId) {
		List<Case> open = coordinator.scoped(brandId).stream()
				.filter(c -> c.getCurrentStage() != Stage.DELIVERED && c.getCurrentStage() != Stage.CLOSED)
				.toList();
		Instant now = Instant.now();
		List<StageCount> stages = stages(open, now);
		if (open.isEmpty()) {
			return new CoordinatorWork(stages, 0, new Documents(0, 0, 0, List.of()), List.of(), List.of());
		}
		Map<UUID, String> cmNames = cmNames();
		return new CoordinatorWork(
				stages,
				(int) open.stream().filter(c -> c.getExceptionState() != ExceptionState.NONE).count(),
				documents(open, cmNames, now),
				queue(open, Stage.CLIENT_REVIEW, cmNames, now),
				queue(open, Stage.READY_TO_DELIVER, cmNames, now));
	}

	private List<StageCount> stages(List<Case> open, Instant now) {
		Map<Stage, List<Case>> byStage = new EnumMap<>(Stage.class);
		for (Case subject : open) {
			byStage.computeIfAbsent(subject.getCurrentStage(), key -> new ArrayList<>()).add(subject);
		}
		return COORDINATOR_STAGES.stream().map(stage -> {
			List<Case> inStage = byStage.getOrDefault(stage, List.of());
			List<Long> hours = inStage.stream().filter(c -> c.getStageEnteredAt() != null)
					.map(c -> waited(c, now)).sorted().toList();
			Long median = null;
			if (!hours.isEmpty()) {
				int size = hours.size();
				median = size % 2 == 1 ? hours.get(size / 2) : (hours.get(size / 2 - 1) + hours.get(size / 2)) / 2;
			}
			return new StageCount(stage, inStage.size(), median);
		}).toList();
	}

	private Documents documents(List<Case> open, Map<UUID, String> cmNames, Instant now) {
		Set<UUID> brandIds = open.stream().map(Case::getBrandId).collect(Collectors.toSet());
		Set<UUID> caseIds = open.stream().map(Case::getId).collect(Collectors.toSet());
		Map<UUID, List<DocumentChecklistItem>> grouped = items.findByBrandIdInAndCaseIdIn(brandIds, caseIds).stream()
				.collect(Collectors.groupingBy(DocumentChecklistItem::getCaseId));
		// The unsent set is brand-wide; only this caller's open cases count.
		Set<UUID> unsent = items.caseIdsWithUnsent(brandIds).stream().filter(caseIds::contains)
				.collect(Collectors.toSet());

		int awaiting = 0;
		int blockers = 0;
		Map<UUID, Case> byId = open.stream().collect(Collectors.toMap(Case::getId, c -> c));
		List<OwedRow> rows = new ArrayList<>();
		for (List<DocumentChecklistItem> list : grouped.values()) {
			awaiting += count(list, ChecklistItemStatus.UPLOADED);
			blockers += count(list, ChecklistItemStatus.MISSING) + count(list, ChecklistItemStatus.INCORRECT);
		}
		// A case is owed if any item is not approved, or the checklist has something unsent — including a
		// case whose items are all approved but one more has not been sent yet.
		Set<UUID> owedIds = new LinkedHashSet<>(unsent);
		grouped.forEach((id, list) -> {
			if (count(list, ChecklistItemStatus.APPROVED) < list.size()) {
				owedIds.add(id);
			}
		});
		for (UUID id : owedIds) {
			Case subject = byId.get(id);
			List<DocumentChecklistItem> list = grouped.getOrDefault(id, List.of());
			rows.add(new OwedRow(id, subject.getCaseCode(), cmName(subject, cmNames),
					subject.getStageEnteredAt() == null ? null : waited(subject, now),
					deadlines.riskOf(subject, now), list.size(), count(list, ChecklistItemStatus.APPROVED),
					count(list, ChecklistItemStatus.UPLOADED), count(list, ChecklistItemStatus.REQUIRED),
					count(list, ChecklistItemStatus.MISSING), count(list, ChecklistItemStatus.INCORRECT),
					unsent.contains(id), null));
		}
		// Worst first, so the cap drops the cases that need least: missing/incorrect, then longest in stage.
		List<OwedRow> shown = rows.stream()
				.sorted(Comparator.comparingInt((OwedRow r) -> r.missing() + r.incorrect()).reversed()
						.thenComparing(OwedRow::waitingBusinessHours,
								Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(ROW_CAP)
				.toList();
		return new Documents(awaiting, blockers, unsent.size(), withLastChased(shown, brandIds));
	}

	/** Chase events for the rows shown only — and none at all for an empty list (a native IN () is a syntax error). */
	private List<OwedRow> withLastChased(List<OwedRow> shown, Set<UUID> brandIds) {
		if (shown.isEmpty()) {
			return shown;
		}
		List<UUID> ids = shown.stream().map(OwedRow::caseId).toList();
		Map<UUID, Instant> last = audit.findCaseActionScoped("CASE", AuditAction.CHASED, ids, List.copyOf(brandIds))
				.stream()
				.collect(Collectors.toMap(AuditEvent::getObjectId, AuditEvent::getCreatedAt,
						BinaryOperator.maxBy(Comparator.naturalOrder())));
		return shown.stream().map(r -> new OwedRow(r.caseId(), r.caseCode(), r.cmName(), r.waitingBusinessHours(),
				r.deadlineRisk(), r.total(), r.approved(), r.uploaded(), r.required(), r.missing(), r.incorrect(),
				r.unsent(), last.get(r.caseId()))).toList();
	}

	private List<QueueRow> queue(List<Case> open, Stage stage, Map<UUID, String> cmNames, Instant now) {
		return open.stream().filter(c -> c.getCurrentStage() == stage)
				.map(c -> new QueueRow(c.getId(), c.getCaseCode(), c.getServiceType(), cmName(c, cmNames),
						c.getDeadline(), deadlines.riskOf(c, now),
						c.getStageEnteredAt() == null ? null : waited(c, now)))
				// Longest-waiting first; a case with no entry time has no wait to rank, so it goes last.
				.sorted(Comparator.comparing(QueueRow::waitingBusinessHours,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(ROW_CAP)
				.toList();
	}

	private long waited(Case subject, Instant now) {
		return calendar.elapsedBusinessTime(subject.getStageEnteredAt(), now).toHours();
	}

	private static int count(List<DocumentChecklistItem> list, ChecklistItemStatus status) {
		return (int) list.stream().filter(i -> i.getStatus() == status).count();
	}

	private static String cmName(Case subject, Map<UUID, String> cmNames) {
		return subject.getAssignedCm() == null ? null : PmMetricsService.name(cmNames, subject.getAssignedCm());
	}

	private Map<UUID, String> cmNames() {
		Map<UUID, String> names = new HashMap<>();
		for (TeamMember member : members.assignable(Role.CASE_MANAGER)) {
			names.put(member.getId(), member.getDisplayName());
		}
		return names;
	}
}
