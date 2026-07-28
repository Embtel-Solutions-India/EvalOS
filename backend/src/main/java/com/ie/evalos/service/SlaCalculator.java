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
	private static final Duration EXPERT_ASSIGNMENT = Duration.ofHours(4);
	private static final Duration FIRST_DRAFT = Duration.ofHours(48);
	private static final Duration PM_REVIEW = Duration.ofHours(12);
	private static final Duration CLIENT_REVIEW = Duration.ofHours(48);
	private static final Duration EXPERT_SIGN = Duration.ofHours(24);
	private static final Duration QC_DELIVERY = Duration.ofHours(2);

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
			case EXPERT_ASSIGNMENT -> EXPERT_ASSIGNMENT;
			case DRAFT_GENERATION -> draftBudget(subject);
			case EXPERT_SIGNING -> EXPERT_SIGN;
			case FINAL_DELIVERY -> QC_DELIVERY;
			case CLOSED -> null;
		};
	}

	/**
	 * Inside DRAFT_GENERATION the clock belongs to whichever loop is waiting, and
	 * it restarts each round — {@code stage_entered_at} is restamped by every
	 * transition, so a second PM review gets its own twelve hours.
	 */
	private static Duration draftBudget(Case subject) {
		if (subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING) {
			return CLIENT_REVIEW;
		}
		if (subject.getPmApprovalStatus() == PmApprovalStatus.PENDING) {
			return PM_REVIEW;
		}
		return FIRST_DRAFT;
	}
}
