package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.SlaStatus;

import org.springframework.stereotype.Component;

/**
 * Turns "how long has this case been waiting" into a RAG status. Every budget is
 * in **business** hours on the {@link BusinessCalendar}, so a case that arrives
 * on Friday afternoon is not overdue on Monday morning.
 *
 * <p>Computed on read and refreshed on every transition. The jobs that alert on
 * an At-risk or Overdue case are Unit 19; this only ever answers the question.
 */
@Component
public class SlaCalculator {

	/** Doc collection: three business days, and a business day is eight hours. */
	private static final Duration DOC_COLLECTION = Duration.ofHours(24);
	private static final Duration PM_REVIEW = Duration.ofHours(4);
	private static final Duration FIRST_DRAFT = Duration.ofHours(48);
	private static final Duration DRAFT_REVIEW = Duration.ofHours(12);
	/** Holding an approved draft is a forwarding step, not work — the same budget as delivery. */
	private static final Duration READY_TO_SEND = Duration.ofHours(2);
	private static final Duration CLIENT_REVIEW = Duration.ofHours(48);
	/** The CM forwarding a locked letter to the expert. Half a business day is generous for a send. */
	private static final Duration CLIENT_APPROVAL = Duration.ofHours(4);

	/**
	 * <strong>One business day, and it used to be three.</strong>
	 *
	 * <p>This was {@code ofHours(24)}, which on the {@link BusinessCalendar} is 24 <em>business</em>
	 * hours — three working days, as {@code DOC_COLLECTION} above says in as many words. The
	 * business means one day when it says "24 hours" to an expert, and the 20-hour warning it
	 * asked for was landing two and a half working days in.
	 *
	 * <p><strong>Business hours rather than wall clock, and the reason is not comfort.</strong> A
	 * letter sent Friday 16:00 would be overdue Saturday 16:00 on a wall clock, and
	 * {@code expert_case_offer} counts {@code TIMED_OUT} into the acceptance rate
	 * {@code ExpertMatchService} ranks on — so a wall clock would systematically demote good
	 * experts for EvalOS's own sending time. Eight business hours gives Tuesday 10:00 → Wednesday
	 * 10:00, exactly what wall-clock "24 hours" gives on a working day, while Friday 16:00 falls
	 * due Monday afternoon.
	 *
	 * <p>The separate 20-hour warning is deliberately not a constant: {@link #AT_RISK_FRACTION} is
	 * already 0.75, and 0.75 × 8 = 6 business hours leaves two hours' notice. Two thresholds for
	 * one idea is two things that drift.
	 *
	 * <p><strong>Say "one business day", never "24 hours".</strong> A label beside a clock that
	 * means something else is how the old value survived unnoticed.
	 */
	private static final Duration EXPERT_SIGN = Duration.ofHours(8);

	private static final Duration FINAL_QC = Duration.ofHours(2);
	private static final Duration READY_TO_DELIVER = Duration.ofHours(2);

	/** At risk once three quarters of the budget is spent. */
	private static final double AT_RISK_FRACTION = 0.75;

	private final BusinessCalendar calendar;

	SlaCalculator(BusinessCalendar calendar) {
		this.calendar = calendar;
	}

	public SlaStatus statusOf(Case subject) {
		return statusOf(subject, Instant.now());
	}

	/** Package-private overload so the clock can be pinned in a test. */
	SlaStatus statusOf(Case subject, Instant now) {
		Duration budget = budgetFor(subject);
		if (budget == null || subject.getStageEnteredAt() == null) {
			return null;
		}
		Duration elapsed = calendar.elapsedBusinessTime(subject.getStageEnteredAt(), now);
		if (elapsed.compareTo(budget) >= 0) {
			return SlaStatus.OVERDUE;
		}
		if (elapsed.toMillis() >= budget.toMillis() * AT_RISK_FRACTION) {
			return SlaStatus.AT_RISK;
		}
		return SlaStatus.ON_TRACK;
	}

	/** Null when no clock is running: a closed case, or one in an exception state. */
	private static Duration budgetFor(Case subject) {
		if (subject.getExceptionState() != ExceptionState.NONE) {
			return null;
		}
		return switch (subject.getCurrentStage()) {
			case DOC_COLLECTION -> DOC_COLLECTION;
			case PM_REVIEW -> PM_REVIEW;
			case DRAFT_IN_PROGRESS -> FIRST_DRAFT;
			case DRAFT_REVIEW -> DRAFT_REVIEW;
			case READY_TO_SEND -> READY_TO_SEND;
			case CLIENT_REVIEW -> CLIENT_REVIEW;
			case CLIENT_APPROVAL -> CLIENT_APPROVAL;
			case EXPERT_SIGNING -> EXPERT_SIGN;
			case FINAL_QC -> FINAL_QC;
			case READY_TO_DELIVER -> READY_TO_DELIVER;
			// Nothing is owed to anybody once the client has their letter. Closing is bookkeeping,
			// and a red case whose work is finished is noise on a board that uses red for risk.
			case DELIVERED, CLOSED -> null;
		};
	}
}
