package com.ie.evalos.job;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.SlaCalculator;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Escalates a document collection that has run out of time, to the PM and the GM.
 *
 * <p><strong>It asks {@link SlaCalculator} whether the budget is spent rather than writing
 * "3 business days" a second time.</strong> The day-3 escalation and the {@code DOC_COLLECTION}
 * SLA budget are the <em>same number</em>; restating it here is how this sweep and the board's
 * rail come to disagree about one case, and then somebody has to work out which is right.
 *
 * <p><strong>Business hours, unlike the chases beside it.</strong> That asymmetry is the whole
 * reason the sequence works: wall-clock chases at 24h and 48h against a business-hours
 * escalation produce chase → chase → escalate. Business hours everywhere would fire the first
 * chase and the escalation at the same instant.
 *
 * <p><strong>Idempotency comes from the notification rows</strong>, via
 * {@code NotificationService.alreadyRaised} — one escalation per case, ever. A job that
 * escalated hourly would train the PM to ignore the bell, and Unit 06's centre is the only
 * channel staff have.
 */
@Component
public class DocEscalationSweep implements Sweep {

	private static final String JOB_TYPE = "DOC_ESCALATION";

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final DocumentChecklistItemRepository checklist;
	private final SlaCalculator sla;
	private final NotificationService notifications;
	private final RecipientResolver recipients;
	private final ApplicationEventPublisher events;

	DocEscalationSweep(SweepRunner runner, CaseRepository cases,
			DocumentChecklistItemRepository checklist, SlaCalculator sla,
			NotificationService notifications, RecipientResolver recipients,
			ApplicationEventPublisher events) {
		this.runner = runner;
		this.cases = cases;
		this.checklist = checklist;
		this.sla = sla;
		this.notifications = notifications;
		this.recipients = recipients;
		this.events = events;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.DOC_ESCALATION}")
	public void tick() {
		run();
	}

	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findAllAtStageForSweep(Stage.DOC_COLLECTION),
				this::escalateIfSpent);
	}

	private boolean escalateIfSpent(Case subject) {
		if (checklist.isChecklistComplete(subject.getId())) {
			return false;
		}
		// Once per case. The notification row IS the record, so there is no second "escalated"
		// flag that could disagree with it.
		if (notifications.alreadyRaised(subject.getId(), NotificationType.DOCS_ESCALATED)) {
			return false;
		}

		// The calculator returns null for a case in an exception state — on hold is not late —
		// and that is deliberately not special-cased here: null simply is not OVERDUE.
		SlaStatus status = sla.statusOf(subject);
		if (status != SlaStatus.OVERDUE) {
			return false;
		}

		notifications.create(subject.getBrandId(), recipients.assignedPmAndBrandManagers(subject),
				NotificationType.DOCS_ESCALATED, subject.getId(),
				"Documents are still outstanding past the collection budget.");
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.DOCS_ESCALATION_DAY3, subject));
		return true;
	}
}
