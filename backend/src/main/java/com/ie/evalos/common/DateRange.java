package com.ie.evalos.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The shell's period vocabulary — {@code today}, {@code week}, {@code month}, {@code year} — as one
 * type rather than a {@code switch} per controller.
 *
 * <p><strong>One home for the fact.</strong> This lived as a private {@code startOf} inside
 * {@code MetricsController} until the marketing funnel needed the same window, and a second copy of
 * "month means 30 days" is a second thing that can be wrong. It is an enum rather than a string
 * because {@code code-standards.md} says to model a closed vocabulary with a type; the parse below
 * is the one place a request string becomes it.
 *
 * <p><strong>Every window looks backwards.</strong> That is worth stating because the board's date
 * filter looks <em>forwards</em> to a deadline, using the same four words — {@code ui-context.md}
 * records that collision. Here the question is always "what happened since", so {@code today} is the
 * last 24 hours rather than the calendar day, matching what the metrics dashboards have always done.
 */
public enum DateRange {

	TODAY(1),
	WEEK(7),
	MONTH(30),
	YEAR(365);

	private final int days;

	DateRange(int days) {
		this.days = days;
	}

	/**
	 * @throws InvalidRequestException on anything else. Refused rather than defaulted: silently
	 *                                 answering for a month when the caller asked for a year is a
	 *                                 wrong number that looks right
	 */
	public static DateRange parse(String raw) {
		for (DateRange range : values()) {
			if (range.name().equalsIgnoreCase(raw)) {
				return range;
			}
		}
		throw new InvalidRequestException("range must be one of today, week, month, year");
	}

	/** The start of this window, counting back from {@code to}. */
	public Instant startFrom(Instant to) {
		return to.minus(days, ChronoUnit.DAYS);
	}

	/**
	 * The first whole day of this window, where {@code to} is the last one and <strong>both edges
	 * are inclusive</strong>.
	 *
	 * <p><strong>Not {@code startFrom} converted to a date, and the difference is a real bug that
	 * shipped.</strong> {@code startFrom} describes a half-open instant window — the last 24 hours
	 * — which is right for the metrics dashboards that subtract from an {@code Instant}. GHL's
	 * filter is date-only with both edges inclusive, so reusing that subtraction here made
	 * {@code TODAY} span <em>yesterday and today</em>: a screen headed "today" showing roughly
	 * double GHL's own figure. Every range was one day wide, but only {@code today} was wrong by
	 * 100%.
	 *
	 * <p>Hence {@code days - 1}: a one-day window is the single day {@code to}, and a seven-day
	 * window is {@code to} plus the six before it.
	 */
	public LocalDate startDateFrom(LocalDate to) {
		return to.minusDays(days - 1L);
	}

	/** Lower-case, matching the wire vocabulary the frontend sends. */
	public String wireName() {
		return name().toLowerCase();
	}
}
