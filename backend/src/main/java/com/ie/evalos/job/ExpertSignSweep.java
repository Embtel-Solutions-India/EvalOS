package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.BusinessCalendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Warns when an expert has been sitting on a signature, and prompts a human at the deadline.
 *
 * <p><strong>This sweep never fires a transition, and that is the line the whole package
 * holds.</strong> At 24 business hours it raises a prompt asking a Case Manager to decide —
 * chase, wait, or reassign. It does <em>not</em> call Unit 15's {@code EXPERT_TIMED_OUT}.
 * Timing an expert out reassigns real work and is a production decision (invariant 15's
 * neighbourhood); a job that made it would be deciding, at 3am, something nobody asked a person
 * about. The prompt is the deliverable; the transition stays a human's.
 *
 * <p><strong>Business hours, via {@link BusinessCalendar}.</strong> An expert sent a letter at
 * 16:00 on Friday is not late on Saturday morning. No new calendar code — the one that exists
 * is already tested.
 *
 * <p><strong>The 20h warning is derived from the 24h budget, not restated.</strong> Twenty
 * hours is a warning <em>fraction</em> of the deadline, so it is written as that fraction: if
 * the budget ever changes, the warning moves with it rather than silently becoming a warning
 * that fires after the thing it warns about.
 *
 * <p><strong>⚠ And the honest limit: the expert may never have received their link.</strong>
 * EvalOS sends no mail, so the signing clock runs whether or not the link reached them — gap
 * G15. Unit 17a's portal-links ledger is what makes that visible; this sweep can only report
 * that the clock has run.
 */
@Component
public class ExpertSignSweep implements Sweep {

	private static final String JOB_TYPE = "EXPERT_SIGN";

	private static final Logger log = LoggerFactory.getLogger(ExpertSignSweep.class);

	/**
	 * The signing deadline, in business hours. Unit 15's number, kept here because this is the
	 * only thing that measures it — {@code SlaCalculator} owns the <em>stage</em> budget and
	 * this is a within-stage warning against the same deadline.
	 */
	private static final Duration SIGN_DEADLINE = Duration.ofHours(24);

	/**
	 * The warning point, as a fraction of the deadline rather than a second constant.
	 *
	 * <p>Twenty of twenty-four. Written as arithmetic on the budget so the two cannot drift into
	 * a warning that fires after the deadline it warns about — which is the failure mode of two
	 * hardcoded hour counts sitting next to each other.
	 */
	private static final Duration SIGN_WARNING = SIGN_DEADLINE.multipliedBy(5).dividedBy(6);

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final BusinessCalendar calendar;
	private final NotificationService notifications;
	private final RecipientResolver recipients;

	ExpertSignSweep(SweepRunner runner, CaseRepository cases, BusinessCalendar calendar,
			NotificationService notifications, RecipientResolver recipients) {
		this.runner = runner;
		this.cases = cases;
		this.calendar = calendar;
		this.notifications = notifications;
		this.recipients = recipients;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.EXPERT_SIGN}")
	public void tick() {
		run();
	}

	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findAllAtStageForSweep(Stage.EXPERT_SIGNING),
				this::promptIfLate);
	}

	/**
	 * Who hears about a stalled signature: the Case Manager, who owns the step since Unit 31,
	 * and the Project Manager who oversees it.
	 *
	 * <p>Composed from the two existing resolvers rather than adding a third to
	 * {@code RecipientResolver}. A distinct + concat is cheaper than a method whose only caller
	 * is here, and it keeps the resolver's list of roles-that-mean-something short.
	 */
	private List<UUID> signingOwners(Case subject) {
		return Stream.concat(recipients.assignedCm(subject).stream(),
				recipients.assignedPm(subject).stream()).distinct().toList();
	}

	private boolean promptIfLate(Case subject) {
		Instant entered = subject.getStageEnteredAt();
		if (entered == null) {
			log.warn("Case {} is at EXPERT_SIGNING with no stage_entered_at — not prompting",
					subject.getId());
			return false;
		}

		Duration elapsed = calendar.elapsedBusinessTime(entered, Instant.now());

		// Deadline first: a case that has blown through both thresholds since the last tick
		// should get the prompt that matters, not the warning it has already outrun.
		if (elapsed.compareTo(SIGN_DEADLINE) >= 0) {
			if (notifications.alreadyRaised(subject.getId(), NotificationType.EXPERT_SIGN_OVERDUE)) {
				return false;
			}
			notifications.create(subject.getBrandId(), signingOwners(subject),
					NotificationType.EXPERT_SIGN_OVERDUE, subject.getId(),
					"The signing deadline has passed. Chase the expert, or reassign — this sweep "
							+ "will not time them out for you.");
			return true;
		}

		if (elapsed.compareTo(SIGN_WARNING) >= 0) {
			if (notifications.alreadyRaised(subject.getId(), NotificationType.EXPERT_SIGN_AT_RISK)) {
				return false;
			}
			notifications.create(subject.getBrandId(), signingOwners(subject),
					NotificationType.EXPERT_SIGN_AT_RISK, subject.getId(),
					"The expert has not signed and the deadline is close.");
			return true;
		}

		return false;
	}
}
