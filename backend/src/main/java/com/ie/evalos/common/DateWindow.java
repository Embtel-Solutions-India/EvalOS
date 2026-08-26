package com.ie.evalos.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;

/**
 * One period, resolved to whole days: what the shell's date filter actually means.
 *
 * <p><strong>This replaced a day count, and the replacement was forced rather than tidy.</strong>
 * {@link DateRange} used to carry an {@code int days} and every window was "now minus N days".
 * That cannot express either of the two things the filter now offers: a calendar-to-date period is
 * not a fixed width ("this month" is 1 day wide on the 1st and 31 on the 31st), and
 * {@code LAST_MONTH} does not end today at all. So a range names a period and <em>this</em>
 * resolves it, once, into the pair of dates every caller actually wants.
 *
 * <p><strong>Both edges are inclusive days, never instants.</strong> That is the shape GHL's
 * filter takes natively, and the metrics callers convert through {@link #startInstant()} /
 * {@link #endInstant()} rather than the reverse. The previous code had it the other way round —
 * an instant window converted to dates — and it shipped a bug: subtracting a whole day count made
 * every window one day too wide, so a screen headed "today" showed yesterday's rows too, roughly
 * doubling the figure. Days are the primitive here because days are what the labels promise.
 *
 * <p><strong>The zone is carried, not re-derived.</strong> A window resolved in
 * {@code America/Los_Angeles} must produce its instants in that same zone, or "today" silently
 * means one day to the resolver and another to whoever converts it. Holding the zone in the record
 * makes disagreeing impossible; taking a {@link Clock} in {@link #of} means the zone and "what day
 * is it" come from one source. Tests pass {@code Clock.fixed}, so no boundary case needs a
 * contrived system clock.
 *
 * <p>Lives in {@code common} and depends on nothing: {@code BusinessCalendar} owns the zone and is
 * in {@code service}, so it supplies the clock rather than being imported here. That keeps this a
 * leaf, which is what lets the wire vocabulary have exactly one definition.
 *
 * @param range what the caller asked for, kept so a payload can echo it back and a reader can see
 *              the period the figures actually cover
 * @param from  first day, inclusive
 * @param to    last day, inclusive
 * @param zone  the zone {@code from} and {@code to} are days in
 */
public record DateWindow(DateRange range, LocalDate from, LocalDate to, ZoneId zone) {

	/**
	 * The window a request asked for.
	 *
	 * <p>{@code from} and {@code to} are <strong>required for {@code custom} and refused for
	 * everything else</strong>. Refusing rather than ignoring them is the same call
	 * {@link DateRange#parse} makes about an unknown range name: a caller who writes
	 * {@code ?range=month&from=2026-01-01} means the January window, and quietly answering for
	 * this month instead is a wrong number that looks right. A 400 naming the conflict costs them
	 * one round trip and tells them exactly what to fix.
	 *
	 * @param rawRange the wire name — {@code today}, {@code week}, {@code month}, {@code year},
	 *                 {@code last-month}, {@code last-year} or {@code custom}
	 * @param rawFrom  ISO date, custom only
	 * @param rawTo    ISO date, custom only
	 * @param clock    supplies both "now" and the zone the days are counted in
	 * @throws InvalidRequestException on an unknown range, a missing or unparseable custom edge,
	 *                                 a custom window that ends before it starts, or explicit dates
	 *                                 on a named range
	 */
	public static DateWindow of(String rawRange, String rawFrom, String rawTo, Clock clock) {
		DateRange range = DateRange.parse(rawRange);
		LocalDate today = LocalDate.now(clock);
		ZoneId zone = clock.getZone();

		if (range != DateRange.CUSTOM) {
			if (given(rawFrom) || given(rawTo)) {
				throw new InvalidRequestException(
						"from and to are only accepted with range=custom; " + range.wireName()
								+ " already names its own window");
			}
			return new DateWindow(range, startOf(range, today), endOf(range, today), zone);
		}

		if (!given(rawFrom) || !given(rawTo)) {
			throw new InvalidRequestException("range=custom needs both from and to as ISO dates (yyyy-MM-dd)");
		}
		LocalDate from = date(rawFrom, "from");
		LocalDate to = date(rawTo, "to");
		// Equal is allowed — a single-day custom window is a legitimate question, and it is the
		// same window `today` describes. Only backwards is refused.
		if (from.isAfter(to)) {
			throw new InvalidRequestException("from (" + from + ") is after to (" + to + ")");
		}
		// **No maximum span, deliberately.** The GHL screens already degrade honestly on a window
		// too large to total (DETAIL_ROW_CEILING -> UNAVAILABLE, which tells the reader to narrow
		// it), and the metrics queries are bounded by the table rather than the window. A second
		// limit here would be a second place for "too big" to be defined, and the two would
		// disagree.
		return new DateWindow(DateRange.CUSTOM, from, to, zone);
	}

	private static LocalDate startOf(DateRange range, LocalDate today) {
		return switch (range) {
			case TODAY -> today;
			// Monday, per ISO-8601. Stated because a Sunday-start week is equally common in US
			// business tooling and the choice is invisible in the label: picking one and writing it
			// down is the only way "this week" means the same thing to the screen and the reader.
			case WEEK -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
			case MONTH -> today.withDayOfMonth(1);
			case YEAR -> today.withDayOfYear(1);
			// `minusMonths` first, THEN the first of the month — never `withDayOfMonth(1)` first.
			// On 31 March, minusMonths(1) clamps to 28 February and lands in the right month; the
			// other order would give 1 March minus a month, which is also February, but on 31 May
			// it would give 1 April rather than April at all. One order is right on every date.
			case LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1);
			case LAST_YEAR -> today.minusYears(1).withDayOfYear(1);
			case CUSTOM -> throw new IllegalStateException("custom is resolved from its own dates");
		};
	}

	private static LocalDate endOf(DateRange range, LocalDate today) {
		return switch (range) {
			// The four to-date ranges all end today: that is what "to date" means, and it is why
			// none of them can be a fixed width.
			case TODAY, WEEK, MONTH, YEAR -> today;
			case LAST_MONTH -> today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
			case LAST_YEAR -> today.minusYears(1).with(TemporalAdjusters.lastDayOfYear());
			case CUSTOM -> throw new IllegalStateException("custom is resolved from its own dates");
		};
	}

	private static boolean given(String raw) {
		return raw != null && !raw.isBlank();
	}

	private static LocalDate date(String raw, String field) {
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (DateTimeParseException ex) {
			// The offending value is echoed because the caller sent it and it is not a secret; the
			// expected shape is echoed because "invalid date" alone has cost people an afternoon.
			throw new InvalidRequestException(field + " must be an ISO date (yyyy-MM-dd), not '" + raw + "'", ex);
		}
	}

	/** First instant of the first day, in this window's own zone. */
	public Instant startInstant() {
		return from.atStartOfDay(zone).toInstant();
	}

	/**
	 * The instant the window ends, <strong>exclusive</strong> — midnight opening the day after
	 * {@link #to}.
	 *
	 * <p>Exclusive because a half-open interval is the only bound that cannot drop a row: an
	 * inclusive end would have to be the last representable instant of the day, and anything
	 * stored with finer precision than the bound falls outside it. The days themselves stay
	 * inclusive, which is what the labels promise; only this conversion is half-open.
	 */
	public Instant endInstant() {
		return to.plusDays(1).atStartOfDay(zone).toInstant();
	}

	/**
	 * This window's cache identity.
	 *
	 * <p><strong>The resolved days, not the range name, and that is load-bearing.</strong> Two
	 * different custom windows are both named {@code custom}: keyed by name they would share one
	 * cache row and serve each other's figures, which is undetectable on screen because the two
	 * payloads are identical in shape. Keying on the window makes that impossible by construction,
	 * and fixes a smaller existing fault for free — a row written for {@code month} used to keep
	 * answering for {@code month} after midnight, when "this month" had become a different window.
	 */
	public String key() {
		return from + ".." + to;
	}
}
