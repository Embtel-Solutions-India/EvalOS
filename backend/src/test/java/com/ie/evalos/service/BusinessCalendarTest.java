package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SLA clock, checked over the week that breaks a naive implementation: 4 July
 * 2026 is a Saturday, so Independence Day is observed on Friday the 3rd and the
 * business week runs Thursday → Monday.
 */
class BusinessCalendarTest {

	private final BusinessCalendar calendar = new BusinessCalendar();

	private static Instant pt(int year, int month, int day, int hour) {
		return LocalDateTime.of(year, month, day, hour, 0).atZone(BusinessCalendar.ZONE).toInstant();
	}

	@Test
	void weekendsAndObservedFederalHolidaysAreNotBusinessDays() {
		assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 7, 2)), "Thursday");
		assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 7, 3)), "Independence Day, observed");
		assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 7, 4)), "Saturday");
		assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 7, 5)), "Sunday");
		assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 7, 6)), "Monday");

		// Fourth Thursday of November, so a moving date rather than a fixed one.
		assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 11, 26)), "Thanksgiving");

		// 1 January 2028 is a Saturday: the observance falls back into 2027.
		assertFalse(calendar.isBusinessDay(LocalDate.of(2027, 12, 31)), "New Year's Day, observed");
	}

	@Test
	void elapsedTimeCountsOnlyBusinessHours() {
		// Thursday 16:00 to Monday 09:00: one hour of Thursday, and nothing else,
		// because Friday is the observed holiday and the weekend follows it.
		assertEquals(Duration.ofHours(1),
				calendar.elapsedBusinessTime(pt(2026, 7, 2, 16), pt(2026, 7, 6, 9)));

		// Plus three full 8-hour days to Wednesday close.
		assertEquals(Duration.ofHours(25),
				calendar.elapsedBusinessTime(pt(2026, 7, 2, 16), pt(2026, 7, 8, 17)));

		// Within one day, nothing special happens.
		assertEquals(Duration.ofHours(5),
				calendar.elapsedBusinessTime(pt(2026, 7, 6, 10), pt(2026, 7, 6, 15)));

		assertEquals(Duration.ZERO, calendar.elapsedBusinessTime(pt(2026, 7, 6, 15), pt(2026, 7, 6, 10)));
	}

	/**
	 * The arithmetic answers exactly what the day-by-day scan it replaced did, over years of history: random
	 * spans from 2020 to 2030, plus edges that a counting slip would miss — weekend and holiday boundaries,
	 * a New Year observed in the year before, the DST changes, and spans starting or ending outside hours.
	 */
	@Test
	void elapsedTimeMatchesTheDayByDayScanOverYears() {
		java.util.List<Instant[]> spans = new java.util.ArrayList<>();
		java.util.Random random = new java.util.Random(42);
		long lo = pt(2020, 1, 1, 0).getEpochSecond();
		long hi = pt(2030, 12, 31, 23).getEpochSecond();
		for (int i = 0; i < 3000; i++) {
			long a = lo + (long) (random.nextDouble() * (hi - lo));
			long b = a + (long) (random.nextDouble() * (i % 3 == 0 ? 3 * 365 * 86400L : 20 * 86400L));
			spans.add(new Instant[] { Instant.ofEpochSecond(a), Instant.ofEpochSecond(b) });
		}
		spans.add(new Instant[] { pt(2026, 7, 2, 16), pt(2026, 7, 6, 9) });     // into an observed holiday and weekend
		spans.add(new Instant[] { pt(2026, 7, 3, 12), pt(2026, 7, 4, 12) });    // holiday to Saturday
		spans.add(new Instant[] { pt(2021, 12, 30, 17), pt(2022, 1, 3, 9) });   // New Year 2022 observed 31 Dec 2021
		spans.add(new Instant[] { pt(2027, 12, 31, 8), pt(2028, 1, 3, 18) });   // New Year 2028 observed 31 Dec 2027
		spans.add(new Instant[] { pt(2026, 3, 6, 10), pt(2026, 3, 9, 10) });    // spring forward
		spans.add(new Instant[] { pt(2026, 10, 30, 16), pt(2026, 11, 2, 11) }); // fall back
		spans.add(new Instant[] { pt(2026, 1, 3, 0), pt(2026, 1, 4, 23) });     // a whole weekend
		spans.add(new Instant[] { pt(2020, 1, 1, 0), pt(2030, 12, 31, 23) });   // the whole range
		for (Instant[] span : spans) {
			assertEquals(dayByDay(span[0], span[1]), calendar.elapsedBusinessTime(span[0], span[1]),
					() -> span[0] + " to " + span[1]);
		}
	}

	/** The scan {@code elapsedBusinessTime} used to run, kept as the reference it must agree with. */
	private Duration dayByDay(Instant from, Instant to) {
		if (!to.isAfter(from)) {
			return Duration.ZERO;
		}
		var start = from.atZone(BusinessCalendar.ZONE);
		var end = to.atZone(BusinessCalendar.ZONE);
		Duration total = Duration.ZERO;
		for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
			if (!calendar.isBusinessDay(day)) {
				continue;
			}
			var open = day.atTime(BusinessCalendar.OPEN).atZone(BusinessCalendar.ZONE);
			var close = day.atTime(BusinessCalendar.CLOSE).atZone(BusinessCalendar.ZONE);
			var windowStart = start.isAfter(open) ? start : open;
			var windowEnd = end.isBefore(close) ? end : close;
			if (windowEnd.isAfter(windowStart)) {
				total = total.plus(Duration.between(windowStart, windowEnd));
			}
		}
		return total;
	}

	@Test
	void businessTimeAddsAndSubtractsAcrossTheClosedDays() {
		// Thursday 16:00 + 4 business hours: one hour left on Thursday, three on Monday.
		assertEquals(pt(2026, 7, 6, 12), calendar.plusBusinessTime(pt(2026, 7, 2, 16), Duration.ofHours(4)));

		// And back again — a negative amount subtracts.
		assertEquals(pt(2026, 7, 2, 16), calendar.plusBusinessTime(pt(2026, 7, 6, 12), Duration.ofHours(-4)));
	}
}
