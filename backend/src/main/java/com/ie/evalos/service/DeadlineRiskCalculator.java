package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;

import org.springframework.stereotype.Component;

/**
 * Turns "how long until we have to deliver this" into a RAG band.
 *
 * <p>The sibling of {@link SlaCalculator} and deliberately not a replacement for it: that one
 * measures time spent in the current stage against a stage budget, this one measures time left
 * until {@code deadline}. See {@link DeadlineRisk} for why both exist.
 *
 * <p>Computed on read, like the SLA. Nothing is stored, so changing a deadline reclassifies the
 * case on the next read with no cache to invalidate.
 */
@Component
public class DeadlineRiskCalculator {

	/**
	 * The two band edges, in <strong>business</strong> hours on {@link BusinessCalendar}.
	 *
	 * <p>This is their one home. They come from {@code ui-context.md}'s RAG table — red under
	 * 24h, amber under 48h — and are stated once here rather than restated per query, for the
	 * reason {@code SlaCalculator} holds the stage budgets: a threshold written twice is a
	 * threshold that drifts, and two screens then disagree about one case.
	 */
	private static final Duration RED = Duration.ofHours(24);
	private static final Duration AMBER = Duration.ofHours(48);

	private final BusinessCalendar calendar;

	DeadlineRiskCalculator(BusinessCalendar calendar) {
		this.calendar = calendar;
	}

	public DeadlineRisk riskOf(Case subject) {
		return riskOf(subject, Instant.now());
	}

	/**
	 * Package-private overload so the clock can be pinned in a test, matching
	 * {@link SlaCalculator#statusOf(Case, Instant)}.
	 *
	 * @return null when no clock is running — see {@link #hasNoClock}
	 */
	DeadlineRisk riskOf(Case subject, Instant now) {
		if (hasNoClock(subject)) {
			return null;
		}
		Instant deadline = subject.getDeadline();
		if (!deadline.isAfter(now)) {
			return DeadlineRisk.OVERDUE;
		}
		// Business time, so a Friday-afternoon deadline is not reported as comfortable on
		// Thursday morning and a case that sat over a long weekend is not reported as late.
		Duration remaining = calendar.elapsedBusinessTime(now, deadline);
		if (remaining.compareTo(RED) < 0) {
			return DeadlineRisk.OVERDUE;
		}
		if (remaining.compareTo(AMBER) < 0) {
			return DeadlineRisk.AT_RISK;
		}
		return DeadlineRisk.ON_TRACK;
	}

	/**
	 * Whether this case has no deadline clock, in which case it takes the muted band rather than
	 * a colour.
	 *
	 * <p>Mirrors {@link SlaCalculator}'s null rule exactly, and that is the point: a case holding
	 * an exception state is waiting on somebody outside EvalOS, so colouring it green would
	 * overstate the board's health and colouring it red would blame the wrong party. This is the
	 * {@code slaMix} "unknown" lesson — do not paint a paused case as either.
	 *
	 * <p>A closed case has nothing left to miss, and a case with no deadline never had a promise
	 * to keep.
	 */
	private static boolean hasNoClock(Case subject) {
		return subject.getDeadline() == null
				|| subject.getCurrentStage() == Stage.CLOSED
				|| subject.getExceptionState() != ExceptionState.NONE;
	}
}
