package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.AuditService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chases a client whose documents have not arrived — at 24 and 48 hours, and then never again.
 *
 * <p><strong>Wall-clock, not business hours, and this is not cosmetic.</strong> The
 * {@code DOC_COLLECTION} budget is 24 <em>business</em> hours — three eight-hour days. If these
 * chases were business hours too, the first chase and the day-3 escalation would land at the
 * same moment, and the "48h" chase would arrive at six business days, three days <em>after</em>
 * the escalation. The intended order is chase → chase → escalate, and only wall-clock chases
 * against a business-hours escalation produce it. It is also what the client experiences, which
 * is the same distinction Unit 10 drew for checklist aging.
 *
 * <p><strong>Idempotency comes from the audit trail, not from a job row.</strong> The number of
 * {@code CHASED} rows on the case decides which chase is due: none means the 24h chase, one
 * means the 48h chase, two or more means nothing. That keeps one record of one fact — the
 * reasoning Unit 05a used to refuse a {@code paid_by} column — and it makes the sweep correct on
 * its first run after any outage, however long.
 *
 * <p><strong>⚠ What this sweep can no longer do, and it is a real limit.</strong> The spec has
 * it publishing {@code checklist.reminder} so GHL chases the client. <strong>Unit 18 was removed
 * from scope on 2026-09-02</strong>, taking the outbound dispatcher with it, so EvalOS has no
 * channel at all (invariant 14) and nothing subscribes to that event. The chase is therefore
 * <strong>a prompt to the Coordinator to chase by hand</strong>, not a message to the client —
 * so the sweep raises that prompt itself, naming which of the two chases is due. The event is
 * still published, unchanged, so the day a channel exists this sweep needs no edit; but nobody
 * should read "chase" here as "the client was contacted".
 *
 * <p><strong>A case on hold is still chased.</strong> Every other SLA sweep skips exception
 * states; this one does not, matching Unit 10's deliberate choice to keep held cases on the
 * checklist board — "on hold awaiting client" is exactly the case whose documents have not come.
 */
@Component
public class DocChaseSweep implements Sweep {

	private static final String JOB_TYPE = "DOC_CHASE";

	private static final Logger log = LoggerFactory.getLogger(DocChaseSweep.class);

	/** Wall-clock, deliberately. See the class comment for why business hours breaks the order. */
	private static final Duration FIRST_CHASE = Duration.ofHours(24);
	private static final Duration SECOND_CHASE = Duration.ofHours(48);

	/** Two chases and no more. A third would be nagging, and nobody decided to nag. */
	private static final int MAX_CHASES = 2;

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final DocumentChecklistItemRepository checklist;
	private final AuditEventRepository auditTrail;
	private final AuditService audit;
	private final NotificationService notifications;
	private final RecipientResolver recipients;
	private final ApplicationEventPublisher events;

	DocChaseSweep(SweepRunner runner, CaseRepository cases, DocumentChecklistItemRepository checklist,
			AuditEventRepository auditTrail, AuditService audit, NotificationService notifications,
			RecipientResolver recipients, ApplicationEventPublisher events) {
		this.runner = runner;
		this.cases = cases;
		this.checklist = checklist;
		this.auditTrail = auditTrail;
		this.audit = audit;
		this.notifications = notifications;
		this.recipients = recipients;
		this.events = events;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.DOC_CHASE}")
	public void tick() {
		run();
	}

	/** Separated from {@link #tick} so the GM's "Run now" reaches the same code path. */
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findAllAtStageForSweep(Stage.DOC_COLLECTION),
				this::chaseIfDue);
	}

	private boolean chaseIfDue(Case subject) {
		if (checklist.isChecklistComplete(subject.getId())) {
			return false;
		}

		Instant entered = subject.getStageEnteredAt();
		if (entered == null) {
			// Nothing to measure from. Louder than silence because it means a stage transition
			// did not stamp its own clock, which is a defect elsewhere.
			log.warn("Case {} is at DOC_COLLECTION with no stage_entered_at — not chasing", subject.getId());
			return false;
		}

		long chasesSoFar = countChases(subject);
		if (chasesSoFar >= MAX_CHASES) {
			return false;
		}

		Duration waited = Duration.between(entered, Instant.now());
		Duration due = chasesSoFar == 0 ? FIRST_CHASE : SECOND_CHASE;
		if (waited.compareTo(due) < 0) {
			return false;
		}

		// The audit row is what makes the next tick skip this: the record and the idempotency
		// are the same row, so they cannot disagree. Written as a SYSTEM event — there is no
		// authenticated actor, and the brand comes from the case rather than from a default.
		long chaseNumber = chasesSoFar + 1;
		audit.recordSystemEvent(subject.getBrandId(), "CASE", subject.getId(), AuditAction.CHASED, null,
				"document chase " + chaseNumber + " of " + MAX_CHASES);
		// No alreadyRaised guard, deliberately: both chases must be seen, and the audit rows
		// counted above are already what stops there being a third.
		notifications.create(subject.getBrandId(), recipients.coordinators(subject.getBrandId()),
				NotificationType.DOC_CHASE_DUE, subject.getId(),
				"Documents are still outstanding — chase " + chaseNumber + " of " + MAX_CHASES
						+ " is due. EvalOS cannot send it, so this one is by hand.");
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_REMINDER, subject));
		return true;
	}

	private long countChases(Case subject) {
		return auditTrail.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", subject.getId()).stream()
				.filter((row) -> row.getAction() == AuditAction.CHASED)
				.count();
	}
}
