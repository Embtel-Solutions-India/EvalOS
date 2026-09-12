package com.ie.evalos.job;

import java.util.List;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.SlaCalculator;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the stored {@code sla_status} honest, and tells the stage's owner when a case tips into
 * breach.
 *
 * <p><strong>It notifies on the transition, never on the state.</strong> A job that alerted
 * every thirty minutes about the same breached case would train everyone to ignore the bell —
 * and Unit 06's notification centre is the only channel staff have, so an ignored bell is a
 * channel lost. The stored status is what makes that possible: it is compared, not just
 * overwritten, and the notification only fires when the comparison changes.
 *
 * <p>That stored column is also the idempotency, in the same way the {@code CHASED} rows are
 * the doc chase's — one record of one fact, rather than a job row asserting the same thing.
 *
 * <p><strong>This sweep refreshes a column; it does not move a case.</strong> No sweep in this
 * package calls a transition. {@code sla_status} is a derived label, and changing it is not a
 * lifecycle event — whereas a job that moved a case would be making a production decision
 * nobody asked a human about.
 *
 * <p><strong>Cases in an exception state are skipped for free.</strong> {@link SlaCalculator}
 * returns null for them — on hold is not late — and a null is neither {@code AT_RISK} nor
 * {@code OVERDUE}, so there is no special case to write here.
 */
@Component
public class StageSlaSweep implements Sweep {

	private static final String JOB_TYPE = "STAGE_SLA";

	/** Terminal stages have no clock; refreshing their status would be rewriting history. */
	private static final List<Stage> TERMINAL = List.of(Stage.DELIVERED, Stage.CLOSED);

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final SlaCalculator sla;
	private final NotificationService notifications;
	private final RecipientResolver recipients;

	StageSlaSweep(SweepRunner runner, CaseRepository cases, SlaCalculator sla,
			NotificationService notifications, RecipientResolver recipients) {
		this.runner = runner;
		this.cases = cases;
		this.sla = sla;
		this.notifications = notifications;
		this.recipients = recipients;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.STAGE_SLA}")
	public void tick() {
		run();
	}

	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findActiveForSweep(TERMINAL), this::refresh);
	}

	private boolean refresh(Case subject) {
		SlaStatus computed = sla.statusOf(subject);
		if (computed == null || computed == subject.getSlaStatus()) {
			return false;
		}

		SlaStatus previous = subject.getSlaStatus();
		subject.setSlaStatus(computed);
		cases.save(subject);

		// Only on the way IN to breach. A case recovering to ON_TRACK is good news that does
		// not need a bell, and re-notifying on every worsening step would be the same noise the
		// transition rule exists to prevent.
		boolean intoBreach = (computed == SlaStatus.AT_RISK || computed == SlaStatus.OVERDUE)
				&& previous != computed
				&& !(previous == SlaStatus.OVERDUE && computed == SlaStatus.AT_RISK);
		if (!intoBreach) {
			return true;
		}

		notifications.create(subject.getBrandId(), recipients.assignedPmAndBrandManagers(subject),
				computed == SlaStatus.OVERDUE ? NotificationType.SLA_OVERDUE : NotificationType.SLA_AT_RISK,
				subject.getId(),
				computed == SlaStatus.OVERDUE ? "This case has passed its stage deadline."
						: "This case is approaching its stage deadline.");
		return true;
	}
}
