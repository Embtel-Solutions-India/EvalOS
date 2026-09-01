package com.ie.evalos.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The deadline clock, which is not the SLA clock.
 *
 * <p>Anchored on Monday 6 July 2026 at 09:00 PT — the Monday after the observed Independence
 * Day holiday {@code BusinessCalendarTest} uses, so the week that follows is five clean business
 * days. At eight business hours a day, the band edges land as: Wednesday close = 24h, and the
 * following Monday close = 48h.
 */
class DeadlineRiskCalculatorTest {

	private final DeadlineRiskCalculator calculator = new DeadlineRiskCalculator(new BusinessCalendar());

	/** Monday 6 July 2026, 09:00 PT — the start of a clean business week. */
	private static final Instant NOW = pt(2026, 7, 6, 9);

	private static Instant pt(int year, int month, int day, int hour) {
		return LocalDateTime.of(year, month, day, hour, 0).atZone(BusinessCalendar.ZONE).toInstant();
	}

	private static Case dueAt(Instant deadline) {
		Case subject = new Case(UUID.randomUUID(), "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
		subject.setDeadline(deadline);
		return subject;
	}

	@Test
	void aDeadlineFurtherOffThanFortyEightBusinessHoursIsOnTrack() {
		// Tuesday of the following week: comfortably past the amber edge.
		assertEquals(DeadlineRisk.ON_TRACK, calculator.riskOf(dueAt(pt(2026, 7, 14, 12)), NOW));
	}

	@Test
	void insideFortyEightBusinessHoursIsAtRisk() {
		// Monday 13th at 16:00 is 47 business hours out — one hour inside the amber edge.
		assertEquals(DeadlineRisk.AT_RISK, calculator.riskOf(dueAt(pt(2026, 7, 13, 16)), NOW));
	}

	@Test
	void insideTwentyFourBusinessHoursIsRed() {
		// Wednesday 16:00 is 23 business hours out — one hour inside the red edge.
		assertEquals(DeadlineRisk.OVERDUE, calculator.riskOf(dueAt(pt(2026, 7, 8, 16)), NOW));
	}

	/**
	 * The edges are exclusive, so a case sitting exactly on one takes the calmer band. Asserted
	 * because "under 24h" and "24h or less" differ by a whole band on the tile the PM watches.
	 */
	@Test
	void theBandEdgesThemselvesAreNotYetInTheWorseBand() {
		// Wednesday close is exactly 24 business hours out: amber, not red.
		assertEquals(DeadlineRisk.AT_RISK, calculator.riskOf(dueAt(pt(2026, 7, 8, 17)), NOW));
		// The following Monday's close is exactly 48: green, not amber.
		assertEquals(DeadlineRisk.ON_TRACK, calculator.riskOf(dueAt(pt(2026, 7, 13, 17)), NOW));
	}

	@Test
	void aDeadlineAlreadyPastIsRed() {
		assertEquals(DeadlineRisk.OVERDUE, calculator.riskOf(dueAt(pt(2026, 7, 2, 10)), NOW));
	}

	/** The boundary the calendar itself cannot answer: it returns ZERO for a non-positive span. */
	@Test
	void aDeadlineExactlyNowIsRedRatherThanOnTrack() {
		assertEquals(DeadlineRisk.OVERDUE, calculator.riskOf(dueAt(NOW), NOW));
	}

	/**
	 * Weekends do not count against the clock. Friday 16:00 with a Monday 12:00 deadline is
	 * four business hours, not sixty-eight wall-clock ones — the whole reason this runs on the
	 * business calendar.
	 */
	@Test
	void theWeekendDoesNotBurnTheClock() {
		Instant fridayAfternoon = pt(2026, 7, 10, 16);
		assertEquals(DeadlineRisk.OVERDUE, calculator.riskOf(dueAt(pt(2026, 7, 13, 12)), fridayAfternoon));
		// Wall-clock, the same span would be nearly three days and read as comfortable.
	}

	@Test
	void aCaseHoldingAnExceptionStateRunsNoClock() {
		Case held = dueAt(pt(2026, 7, 6, 10));
		held.setExceptionState(ExceptionState.ON_HOLD_AWAITING_CLIENT);
		assertNull(calculator.riskOf(held, NOW),
				"a paused case is neither healthy nor breaching — it gets the muted band");
	}

	@Test
	void aClosedCaseRunsNoClock() {
		Case closed = new Case(UUID.randomUUID(), "IE-2026-0002", Stage.CLOSED);
		closed.setDeadline(pt(2026, 7, 6, 10));
		assertNull(calculator.riskOf(closed, NOW));
	}

	@Test
	void aCaseWithNoDeadlineRunsNoClock() {
		assertNull(calculator.riskOf(dueAt(null), NOW));
	}
}
