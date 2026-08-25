package com.ie.evalos.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Case Manager's own docket — the CRM build spec's "Case Manager sees on dashboard".
 *
 * <p>The spec asks for <em>lists</em>, not only counts: my active cases with the client, product,
 * deadline, PM strategy notes, stage and expert; a priority queue ordered by deadline; the draft
 * status board; the client feedback log; and expert signing with a prompt when it goes overdue.
 * All of it is one scoped read plus two batched lookups.
 *
 * <p><strong>Scope needs no filter of its own.</strong> A Case Manager is {@code Tier.SELF}, so
 * {@link CaseLifecycleService#list} already returns only cases naming them. The explicit filter
 * below is for the GM, who is on this endpoint as a superuser and would otherwise be handed the
 * whole brand's work presented as one person's docket.
 */
@Service
public class CaseManagerMetricsService {

	private static final Logger log = LoggerFactory.getLogger(CaseManagerMetricsService.class);

	/**
	 * The build spec's own number: "flag if consistently >30%".
	 *
	 * <p>Not a threshold picked here — and it was 40% in the first cut of this service, which
	 * would have kept quiet through a rate the business considers worth a conversation.
	 */
	private static final int REVISION_RATE_FLAG_PCT = 30;

	/**
	 * Below this many cases, a rate is not a signal.
	 *
	 * <p>At the NFR scale — 50–100 cases per brand per month across a handful of Case Managers —
	 * one person's rate is a small sample. The spec says "flag if <em>consistently</em> >30%", and
	 * this is what makes "consistently" mean something: a coaching flag off four cases costs
	 * somebody a conversation they did not earn.
	 */
	private static final int MIN_CASES_FOR_COMPARISON = 8;

	private final CaseLifecycleService lifecycle;
	private final DeadlineRiskCalculator deadlines;
	private final ContactSnapshotRepository contacts;
	private final ExpertRepository experts;
	private final AuditEventRepository auditEvents;
	private final ObjectMapper objectMapper;

	CaseManagerMetricsService(CaseLifecycleService lifecycle, DeadlineRiskCalculator deadlines,
			ContactSnapshotRepository contacts, ExpertRepository experts, AuditEventRepository auditEvents,
			ObjectMapper objectMapper) {
		this.lifecycle = lifecycle;
		this.deadlines = deadlines;
		this.contacts = contacts;
		this.experts = experts;
		this.auditEvents = auditEvents;
		this.objectMapper = objectMapper;
	}

	/**
	 * One row of "my active cases", carrying what the spec lists for it.
	 *
	 * @param strategyNotes the PM's notes. Sent because the spec puts a notes panel on this
	 *                      dashboard and the Case Manager is on {@code SEES_STRATEGY_NOTES} — the
	 *                      one role that reads them without writing.
	 * @param signingOverdue expert signing past its 24-hour budget. The spec asks for a "reassign
	 *                      prompt"; reassignment is PM/ENM-gated, so what the CM gets is the flag,
	 *                      which is the escalation they actually hold.
	 */
	public record MyCase(
			UUID id,
			String caseCode,
			String clientName,
			ServiceType serviceType,
			Instant deadline,
			DeadlineRisk deadlineRisk,
			Stage stage,
			String expertName,
			ExpertSignStatus expertSignStatus,
			boolean signingOverdue,
			PmApprovalStatus pmApprovalStatus,
			int draftVersionCount,
			ClientApprovalStatus clientApprovalStatus,
			String strategyNotes) {
	}

	/** One entry in the client feedback log: what the client asked for, and when. */
	public record ClientFeedback(UUID caseId, String caseCode, Instant at, String note) {
	}

	/**
	 * @param critical   cases in {@code DeadlineRisk.OVERDUE} — the **red band**, past the promised
	 *                   date *or* inside 24 business hours. Named for the band rather than "today",
	 *                   because it is not a calendar question.
	 * @param atRisk     the amber band: inside 48 business hours.
	 * @param comparable whether the two rates rest on enough cases to be worth reading against
	 *                   anybody else's. False means "shown, not judged".
	 * @param revisionRateFlagged the spec's "consistently >30%" — true only when `comparable`.
	 */
	public record CaseManagerMetrics(
			List<MyCase> cases,
			List<ClientFeedback> clientFeedback,
			int active,
			int critical,
			int atRisk,
			int draftsWithPm,
			int revisionsRequested,
			int awaitingExpertSignature,
			int expertOverdue,
			int deliveredOnTimePct,
			int delivered,
			Integer revisionRatePct,
			Integer clientRevisionRatePct,
			boolean comparable,
			boolean revisionRateFlagged) {
	}

	@Transactional(readOnly = true)
	public CaseManagerMetrics forCaller() {
		TenantContext ctx = TenantContext.current();
		UUID me = ctx.memberId();
		List<Case> mine = lifecycle.list(null, null, null).stream()
				.filter(subject -> me != null && me.equals(subject.getAssignedCm()))
				.toList();
		Instant now = Instant.now();

		Map<UUID, String> clients = clientNames(mine);
		Map<UUID, String> expertNames = expertNames(mine);

		List<MyCase> cases = mine.stream()
				.filter(subject -> subject.getCurrentStage() != Stage.CLOSED)
				.map(subject -> row(subject, now, clients, expertNames))
				// The priority queue *is* this list: soonest deadline first, undated last. The spec
				// wants "due today at top", and ordering by deadline puts it there without a second
				// list that could disagree with this one about the same case.
				.sorted(Comparator.comparing(MyCase::deadline,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();

		return build(mine, cases, clientFeedback(mine, ctx), now);
	}

	private MyCase row(Case subject, Instant now, Map<UUID, String> clients, Map<UUID, String> expertNames) {
		return new MyCase(
				subject.getId(),
				subject.getCaseCode(),
				subject.getContactId() == null ? null : clients.get(subject.getContactId()),
				subject.getServiceType(),
				subject.getDeadline(),
				deadlines.riskOf(subject, now),
				subject.getCurrentStage(),
				subject.getExpertId() == null ? null : expertNames.get(subject.getExpertId()),
				subject.getExpertSignStatus(),
				subject.getExpertSignStatus() == ExpertSignStatus.OVERDUE,
				subject.getPmApprovalStatus(),
				subject.getDraftVersionCount(),
				subject.getClientApprovalStatus(),
				subject.getPmStrategyNotes());
	}

	private CaseManagerMetrics build(List<Case> mine, List<MyCase> cases, List<ClientFeedback> feedback,
			Instant now) {
		int active = 0;
		int critical = 0;
		int atRisk = 0;
		int withPm = 0;
		int returned = 0;
		int awaitingSignature = 0;
		int expertOverdue = 0;
		int delivered = 0;
		int onTime = 0;
		int revised = 0;

		for (Case subject : mine) {
			if (subject.getCurrentStage() != Stage.CLOSED) {
				active++;
			}
			DeadlineRisk risk = deadlines.riskOf(subject, now);
			if (risk == DeadlineRisk.OVERDUE) {
				critical++;
			}
			else if (risk == DeadlineRisk.AT_RISK) {
				atRisk++;
			}
			if (subject.getPmApprovalStatus() == PmApprovalStatus.PENDING) {
				withPm++;
			}
			if (subject.getPmApprovalStatus() == PmApprovalStatus.RETURNED) {
				returned++;
			}
			if (subject.getExpertSignStatus() == ExpertSignStatus.PENDING) {
				awaitingSignature++;
			}
			if (subject.getExpertSignStatus() == ExpertSignStatus.OVERDUE) {
				expertOverdue++;
			}
			if (subject.getDraftVersionCount() > 1) {
				revised++;
			}
			if (subject.getDeliveryDate() != null) {
				delivered++;
				if (subject.getDeadline() == null
						|| !subject.getDeliveryDate().isAfter(subject.getDeadline())) {
					onTime++;
				}
			}
		}

		boolean comparable = mine.size() >= MIN_CASES_FOR_COMPARISON;
		Integer revisionRate = mine.isEmpty() ? null : Math.round(revised * 100f / mine.size());
		// Distinct cases the client came back on, not the number of times they came back: a client
		// who asked twice on one case is one case with a revision request, and counting the rows
		// would let a single unhappy client look like a pattern across a docket.
		long casesWithClientRevisions = feedback.stream().map(ClientFeedback::caseId).distinct().count();

		return new CaseManagerMetrics(cases, feedback, active, critical, atRisk, withPm, returned,
				awaitingSignature, expertOverdue,
				delivered == 0 ? 0 : Math.round(onTime * 100f / delivered), delivered,
				revisionRate,
				mine.isEmpty() ? null : Math.round(casesWithClientRevisions * 100f / mine.size()),
				comparable,
				comparable && revisionRate != null && revisionRate > REVISION_RATE_FLAG_PCT);
	}

	/**
	 * What clients have asked to be changed, newest first.
	 *
	 * <p>Read from {@code CLIENT_REVISION_REQUESTED} rows, which is why that action exists: it used
	 * to share {@code UPDATED} with strategy-note edits, deadline changes and most of the draft
	 * loop, so there was nothing to filter on. Rows written before it stay {@code UPDATED}, so this
	 * log and the rate built from it are forward-looking.
	 */
	private List<ClientFeedback> clientFeedback(List<Case> mine, TenantContext ctx) {
		if (mine.isEmpty() || ctx.brandId() == null) {
			return List.of();
		}
		Map<UUID, String> codes = new HashMap<>();
		mine.forEach(subject -> codes.put(subject.getId(), subject.getCaseCode()));

		return auditEvents
				.findCaseActionScoped("CASE", AuditAction.CLIENT_REVISION_REQUESTED,
						codes.keySet(), List.of(ctx.brandId()))
				.stream()
				.map(event -> new ClientFeedback(event.getObjectId(), codes.get(event.getObjectId()),
						event.getCreatedAt(), noteOf(event)))
				.sorted(Comparator.comparing(ClientFeedback::at).reversed())
				.toList();
	}

	/**
	 * The reason the client gave, out of the stored snapshot's free-text note.
	 *
	 * <p>Parsed with Jackson through the same {@link CaseLifecycleService.CaseSnapshot} shape the
	 * trail writes, not by hunting for a substring — a hand-rolled reader would break on any
	 * escaped quote in what a client typed, which is exactly the kind of text clients type.
	 *
	 * <p>Swallows a parse failure for the reason {@code CaseTimelineService} does: audit rows are
	 * permanent and the snapshot shape has already changed once, so a log that throws on one old
	 * row is worse than one missing a sentence.
	 */
	private String noteOf(AuditEvent event) {
		String json = event.getAfterSnapshot();
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			CaseLifecycleService.CaseSnapshot snapshot =
					objectMapper.readValue(json, CaseLifecycleService.CaseSnapshot.class);
			return snapshot == null ? null : snapshot.note();
		}
		catch (Exception ex) {
			log.debug("Client-revision snapshot not readable; showing the entry without its note", ex);
			return null;
		}
	}

	/** One query for every client name, not one per row — the shape {@code CaseBoardService} uses. */
	private Map<UUID, String> clientNames(List<Case> cases) {
		List<UUID> ids = cases.stream().map(Case::getContactId).filter(java.util.Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			contacts.findAllById(ids).forEach(contact -> names.put(contact.getId(), contact.getFullName()));
		}
		return names;
	}

	private Map<UUID, String> expertNames(List<Case> cases) {
		List<UUID> ids = cases.stream().map(Case::getExpertId).filter(java.util.Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			for (Expert expert : experts.findAllById(ids)) {
				names.put(expert.getId(), expert.getFullName());
			}
		}
		return names;
	}
}
