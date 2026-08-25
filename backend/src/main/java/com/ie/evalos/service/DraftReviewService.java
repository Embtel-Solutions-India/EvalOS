package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.ExpertRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The draft review workspace: every draft in flight, what state it is in, and how far along it is.
 *
 * <p>Built on {@link CaseLifecycleService#list}, the caller's already-scoped read, like every other
 * Unit 22 figure — so this screen can never show a draft its reader could not open.
 *
 * <p><strong>Nothing here is a new column.</strong> The status, the priority and the progress
 * checklist are all derived from fields the lifecycle already maintains. That matters because two
 * of them look like they need storage and do not: see {@code priority} and {@link Milestone}.
 */
@Service
public class DraftReviewService {

	/** The PM's own review budget, from the one place that owns it. */
	private static final Duration PM_REVIEW_BUDGET = Duration.ofHours(12);

	private final CaseLifecycleService lifecycle;
	private final ContactSnapshotRepository contacts;
	private final ExpertRepository experts;
	private final DeadlineRiskCalculator deadlines;
	private final BusinessCalendar calendar;

	DraftReviewService(CaseLifecycleService lifecycle, ContactSnapshotRepository contacts,
			ExpertRepository experts, DeadlineRiskCalculator deadlines, BusinessCalendar calendar) {
		this.lifecycle = lifecycle;
		this.contacts = contacts;
		this.experts = experts;
		this.deadlines = deadlines;
		this.calendar = calendar;
	}

	/**
	 * Where a draft sits, as one value the screen can tab by.
	 *
	 * <p>Derived from {@code pm_approval_status} and the stage rather than stored: a draft's state
	 * is already fully described by those two, and a status column beside them would be a third
	 * thing that can disagree.
	 */
	public enum DraftStatus {
		/** Submitted and sitting with the Project Manager. */
		PENDING_REVIEW,
		/** The PM sent it back. The Case Manager owns it again. */
		REVISIONS_REQUESTED,
		/** PM-approved and signed, waiting on the QC gate out of Expert Signing. */
		READY_FOR_QC,
		/** Through QC — the work is done and the Coordinator has it. */
		APPROVED
	}

	/**
	 * One step of the progress checklist.
	 *
	 * <p><strong>Every milestone is an observable fact about the case</strong>, not a stored
	 * checklist: "expert assigned" is `expert_id != null`, "PM approved" is the approval status.
	 * That is the whole reason there are eight of these and not the reference design's — a
	 * checklist EvalOS cannot observe would be a progress bar that means nothing, and a case
	 * cannot be half-way through a step nobody records.
	 */
	public record Milestone(String label, boolean done) {
	}

	/**
	 * @param priority derived from {@link DeadlineRisk}, **not** a stored field. Decision 5 of this
	 *                 unit refused an urgency column: the deadline already expresses urgency, and a
	 *                 second flag beside it is a second truth that can disagree. High/medium/low
	 *                 here is the red/amber/green band under another name.
	 * @param daysLeft calendar days to the deadline, negative when past. Days rather than business
	 *                 hours because this one is read as a date, and "5 days left" beside a date is
	 *                 what a person checks it against.
	 */
	public record DraftRow(
			UUID id,
			String caseCode,
			String clientName,
			ServiceType serviceType,
			String expertName,
			Instant draftUpdated,
			DraftStatus status,
			DeadlineRisk priority,
			Instant deadline,
			Long daysLeft,
			int draftVersionCount,
			String draftLink,
			int milestonesComplete,
			List<Milestone> milestones) {
	}

	/**
	 * @param avgDraftAgeHours mean business hours the *pending* drafts have been waiting. Mean
	 *                         rather than median here on purpose — it is a workload figure for a
	 *                         queue of single digits, where a median hides the one that is stuck.
	 * @param slaCompliancePct share of pending drafts still inside the 12-hour PM review budget.
	 *                         Null when nothing is pending: no drafts is not 0% compliance.
	 */
	public record DraftSummary(
			int total,
			int pendingReview,
			int revisionsRequested,
			int readyForQc,
			int approved,
			long avgDraftAgeHours,
			Integer slaCompliancePct) {
	}

	public record DraftReview(DraftSummary summary, List<DraftRow> rows) {
	}

	@Transactional(readOnly = true)
	public DraftReview forCaller(UUID brandId) {
		List<Case> scoped = lifecycle.list(null, null, null).stream()
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.filter(DraftReviewService::hasADraft)
				.toList();
		Instant now = Instant.now();

		Map<UUID, String> clients = clientNames(scoped);
		Map<UUID, String> expertNames = expertNames(scoped);

		List<DraftRow> rows = scoped.stream()
				.map(subject -> row(subject, now, clients, expertNames))
				// Oldest first. This is a review queue, and ordering it by deadline would let an old
				// draft with a distant deadline sit forever — which is the case that then arrives
				// late. Same rule the queue screen already followed.
				.sorted(Comparator.comparing(DraftRow::draftUpdated,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();

		return new DraftReview(summary(scoped, rows, now), rows);
	}

	/**
	 * Whether this case has a draft worth showing on a review screen.
	 *
	 * <p>A case that has never had one submitted is not "a draft with no progress" — it is a case
	 * that has not reached drafting, and listing it would put every open case on this screen.
	 */
	private static boolean hasADraft(Case subject) {
		return subject.getDraftVersionCount() > 0;
	}

	private DraftRow row(Case subject, Instant now, Map<UUID, String> clients, Map<UUID, String> expertNames) {
		List<Milestone> milestones = milestones(subject);
		return new DraftRow(
				subject.getId(),
				subject.getCaseCode(),
				subject.getContactId() == null ? null : clients.get(subject.getContactId()),
				subject.getServiceType(),
				subject.getExpertId() == null ? null : expertNames.get(subject.getExpertId()),
				// stage_entered_at is restamped by every transition, so for a draft awaiting review
				// it is exactly "when this wait began" — the same clock the 12h PM budget runs on.
				subject.getStageEnteredAt(),
				statusOf(subject),
				deadlines.riskOf(subject, now),
				subject.getDeadline(),
				subject.getDeadline() == null ? null : ChronoUnit.DAYS.between(now, subject.getDeadline()),
				subject.getDraftVersionCount(),
				subject.getDraftLink(),
				(int) milestones.stream().filter(Milestone::done).count(),
				milestones);
	}

	static DraftStatus statusOf(Case subject) {
		if (subject.getPmApprovalStatus() == PmApprovalStatus.RETURNED) {
			return DraftStatus.REVISIONS_REQUESTED;
		}
		if (subject.getPmApprovalStatus() == PmApprovalStatus.PENDING) {
			return DraftStatus.PENDING_REVIEW;
		}
		// Past the QC gate: the stage itself is the evidence, not a flag.
		if (subject.getCurrentStage() == Stage.FINAL_DELIVERY || subject.getCurrentStage() == Stage.CLOSED) {
			return DraftStatus.APPROVED;
		}
		return DraftStatus.READY_FOR_QC;
	}

	/**
	 * The eight milestones, each one a fact the lifecycle already records.
	 *
	 * <p>Read in pipeline order and **not** assumed monotonic: a case sent back for revisions has
	 * `client_approval_status` nulled by `submitDraft`, so a later step can legitimately go from
	 * done to not-done. The bar is a picture of now, not a high-water mark.
	 */
	static List<Milestone> milestones(Case subject) {
		Stage stage = subject.getCurrentStage();
		boolean pastDocs = stage != Stage.DOC_COLLECTION;
		return List.of(
				new Milestone("Documents collected", pastDocs),
				new Milestone("Expert assigned", subject.getExpertId() != null),
				new Milestone("Draft submitted", subject.getDraftVersionCount() > 0),
				new Milestone("PM approved", subject.getPmApprovalStatus() == PmApprovalStatus.APPROVED),
				new Milestone("Sent to client", subject.getClientApprovalStatus() != null),
				new Milestone("Client approved",
						subject.getClientApprovalStatus() == ClientApprovalStatus.APPROVED),
				new Milestone("Expert signed", subject.getExpertSignStatus() == ExpertSignStatus.SIGNED),
				new Milestone("QC approved",
						stage == Stage.FINAL_DELIVERY || stage == Stage.CLOSED));
	}

	private DraftSummary summary(List<Case> scoped, List<DraftRow> rows, Instant now) {
		int pending = 0;
		int returned = 0;
		int readyForQc = 0;
		int approved = 0;
		long ageHours = 0;
		int insideBudget = 0;

		for (Case subject : scoped) {
			switch (statusOf(subject)) {
				case PENDING_REVIEW -> {
					pending++;
					if (subject.getStageEnteredAt() != null) {
						Duration waited = calendar.elapsedBusinessTime(subject.getStageEnteredAt(), now);
						ageHours += waited.toHours();
						if (waited.compareTo(PM_REVIEW_BUDGET) < 0) {
							insideBudget++;
						}
					}
				}
				case REVISIONS_REQUESTED -> returned++;
				case READY_FOR_QC -> readyForQc++;
				case APPROVED -> approved++;
			}
		}

		return new DraftSummary(rows.size(), pending, returned, readyForQc, approved,
				pending == 0 ? 0 : ageHours / pending,
				// Null, not 0: an empty queue is perfect compliance in the same sense that an
				// unplayed match is not a loss. The tile renders its empty state instead.
				pending == 0 ? null : Math.round(insideBudget * 100f / pending));
	}

	/** One query for every name rather than one per row — the shape `CaseBoardService` uses. */
	private Map<UUID, String> clientNames(List<Case> cases) {
		List<UUID> ids = cases.stream().map(Case::getContactId).filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			contacts.findAllById(ids).forEach(contact -> names.put(contact.getId(), contact.getFullName()));
		}
		return names;
	}

	private Map<UUID, String> expertNames(List<Case> cases) {
		List<UUID> ids = cases.stream().map(Case::getExpertId).filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			for (Expert expert : experts.findAllById(ids)) {
				names.put(expert.getId(), expert.getFullName());
			}
		}
		return names;
	}

}
