package com.ie.evalos.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The clock every SLA is measured on: 09:00–17:00 America/Los_Angeles, weekends
 * and US federal holidays excluded. Timestamps are stored UTC everywhere else;
 * this is the one place a wall clock and a working day exist.
 */
@Component
public class BusinessCalendar {

	public static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

	/**
	 * The system clock, in {@link #ZONE}.
	 *
	 * <p>For {@code DateWindow}, which needs both "what day is it" and "in which zone" and must get
	 * them from one source or "today" can mean two different days inside one request. It lives here
	 * because this class already owns the answer to "what day is it *here*" for every SLA in the
	 * app — a second zone constant beside it is the duplication this avoids.
	 *
	 * <p>A {@link Clock} rather than a {@code LocalDate}: callers that need the date call
	 * {@code LocalDate.now(clock)}, and tests substitute {@code Clock.fixed} to pin a month
	 * boundary without touching the machine's clock.
	 */
	public static Clock clock() {
		return Clock.system(ZONE);
	}

	static final LocalTime OPEN = LocalTime.of(9, 0);
	static final LocalTime CLOSE = LocalTime.of(17, 0);

	/** Per calendar year, computed once. The set for a year never changes. */
	private final Map<Integer, Set<LocalDate>> holidaysByYear = new ConcurrentHashMap<>();

	public boolean isBusinessDay(LocalDate date) {
		return date.getDayOfWeek() != DayOfWeek.SATURDAY
				&& date.getDayOfWeek() != DayOfWeek.SUNDAY
				&& !holidays(date.getYear()).contains(date);
	}

	/** Working time between two instants, ignoring everything outside business hours. */
	public Duration elapsedBusinessTime(Instant from, Instant to) {
		if (!to.isAfter(from)) {
			return Duration.ZERO;
		}
		ZonedDateTime start = from.atZone(ZONE);
		ZonedDateTime end = to.atZone(ZONE);
		Duration total = Duration.ZERO;
		// ponytail: day-by-day scan. Stage-length spans are a handful of iterations at
		// 50-100 cases/brand/month; precompute a business-day index if this ever has to
		// measure years of history.
		for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
			if (!isBusinessDay(day)) {
				continue;
			}
			ZonedDateTime open = day.atTime(OPEN).atZone(ZONE);
			ZonedDateTime close = day.atTime(CLOSE).atZone(ZONE);
			ZonedDateTime windowStart = start.isAfter(open) ? start : open;
			ZonedDateTime windowEnd = end.isBefore(close) ? end : close;
			if (windowEnd.isAfter(windowStart)) {
				total = total.plus(Duration.between(windowStart, windowEnd));
			}
		}
		return total;
	}

	/**
	 * Adds business time to an instant, or subtracts it when the amount is negative.
	 * Time outside business hours costs nothing, so a Friday afternoon plus four
	 * hours lands on Monday morning.
	 */
	public Instant plusBusinessTime(Instant from, Duration amount) {
		boolean forward = !amount.isNegative();
		Duration left = amount.abs();
		ZonedDateTime cursor = from.atZone(ZONE);

		while (!left.isZero()) {
			LocalDate day = cursor.toLocalDate();
			if (!isBusinessDay(day)) {
				cursor = nextBoundary(day, forward);
				continue;
			}
			ZonedDateTime open = day.atTime(OPEN).atZone(ZONE);
			ZonedDateTime close = day.atTime(CLOSE).atZone(ZONE);

			if (forward) {
				if (cursor.isBefore(open)) {
					cursor = open;
				}
				if (!cursor.isBefore(close)) {
					cursor = nextBoundary(day, true);
					continue;
				}
				Duration available = Duration.between(cursor, close);
				if (available.compareTo(left) >= 0) {
					return cursor.plus(left).toInstant();
				}
				left = left.minus(available);
				cursor = nextBoundary(day, true);
			}
			else {
				if (cursor.isAfter(close)) {
					cursor = close;
				}
				if (!cursor.isAfter(open)) {
					cursor = nextBoundary(day, false);
					continue;
				}
				Duration available = Duration.between(open, cursor);
				if (available.compareTo(left) >= 0) {
					return cursor.minus(left).toInstant();
				}
				left = left.minus(available);
				cursor = nextBoundary(day, false);
			}
		}
		return cursor.toInstant();
	}

	private static ZonedDateTime nextBoundary(LocalDate day, boolean forward) {
		return forward
				? day.plusDays(1).atTime(OPEN).atZone(ZONE)
				: day.minusDays(1).atTime(CLOSE).atZone(ZONE);
	}

	private Set<LocalDate> holidays(int year) {
		return holidaysByYear.computeIfAbsent(year, BusinessCalendar::federalHolidays);
	}

	/** The eleven US federal holidays for one year, as actually observed. */
	private static Set<LocalDate> federalHolidays(int year) {
		Set<LocalDate> dates = new HashSet<>();
		dates.add(observed(LocalDate.of(year, Month.JANUARY, 1)));
		dates.add(nth(year, Month.JANUARY, 3, DayOfWeek.MONDAY));            // MLK Day
		dates.add(nth(year, Month.FEBRUARY, 3, DayOfWeek.MONDAY));           // Washington's Birthday
		dates.add(LocalDate.of(year, Month.MAY, 31)
				.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));  // Memorial Day
		dates.add(observed(LocalDate.of(year, Month.JUNE, 19)));             // Juneteenth
		dates.add(observed(LocalDate.of(year, Month.JULY, 4)));              // Independence Day
		dates.add(nth(year, Month.SEPTEMBER, 1, DayOfWeek.MONDAY));          // Labor Day
		dates.add(nth(year, Month.OCTOBER, 2, DayOfWeek.MONDAY));            // Columbus Day
		dates.add(observed(LocalDate.of(year, Month.NOVEMBER, 11)));         // Veterans Day
		dates.add(nth(year, Month.NOVEMBER, 4, DayOfWeek.THURSDAY));         // Thanksgiving
		dates.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));

		// New Year's Day on a Saturday is observed on the Friday before, which falls in
		// the previous year — so this year's set has to carry next year's shift.
		LocalDate nextNewYear = observed(LocalDate.of(year + 1, Month.JANUARY, 1));
		if (nextNewYear.getYear() == year) {
			dates.add(nextNewYear);
		}
		return Set.copyOf(dates);
	}

	private static LocalDate nth(int year, Month month, int ordinal, DayOfWeek day) {
		return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, day));
	}

	/** A fixed-date holiday at the weekend is observed on the nearest weekday. */
	private static LocalDate observed(LocalDate date) {
		return switch (date.getDayOfWeek()) {
			case SATURDAY -> date.minusDays(1);
			case SUNDAY -> date.plusDays(1);
			default -> date;
		};
	}
}
