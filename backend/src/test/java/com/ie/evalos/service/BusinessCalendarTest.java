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

	@Test
	void businessTimeAddsAndSubtractsAcrossTheClosedDays() {
		// Thursday 16:00 + 4 business hours: one hour left on Thursday, three on Monday.
		assertEquals(pt(2026, 7, 6, 12), calendar.plusBusinessTime(pt(2026, 7, 2, 16), Duration.ofHours(4)));

		// And back again — a negative amount subtracts.
		assertEquals(pt(2026, 7, 2, 16), calendar.plusBusinessTime(pt(2026, 7, 6, 12), Duration.ofHours(-4)));
	}
}
