package com.ie.evalos.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each period actually means, pinned at the boundaries where it is easy to be wrong.
 *
 * <p><strong>Every case pins a fixed clock rather than reading the machine's.</strong> That is the
 * whole reason {@code DateWindow.of} takes a {@link Clock}: the interesting cases are the 1st of a
 * month, a Monday, a leap day and the January where "last month" is in the previous year, and a
 * test that waits for the calendar to reach one of those tests nothing on the other 360 days.
 *
 * <p>The zone is the business's, because that is what production passes and because a window
 * resolved in one zone and converted in another is precisely the bug the record's own comment
 * warns about.
 */
class DateWindowTest {

	private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

	/** A Wednesday, mid-month, mid-year — the ordinary case everything else is measured against. */
	private static DateWindow on(String date, String range) {
		return DateWindow.of(range, null, null, fixed(date));
	}

	private static Clock fixed(String date) {
		// Noon local, so the window can never be an artefact of a UTC day boundary crossing: at
		// 00:30 LA it is already tomorrow in UTC, and a resolver that leaked UTC would pass at
		// noon and fail at midnight. `zoneCrossingIsResolvedInTheBusinessZone` pins that directly.
		return Clock.fixed(LocalDate.parse(date).atTime(12, 0).atZone(LA).toInstant(), LA);
	}

	@Test
	void theFourToDateRangesAllEndToday() {
		for (String range : new String[] { "today", "week", "month", "year" }) {
			assertThat(on("2026-08-26", range).to())
					.describedAs("%s must end today — that is what \"to date\" means", range)
					.isEqualTo(LocalDate.parse("2026-08-26"));
		}
	}

	@Test
	void todayIsTheCalendarDayAndNotTheLastTwentyFourHours() {
		DateWindow window = on("2026-08-26", "today");

		// One day, both edges the same date. The predecessor of this class described "today" as an
		// instant window a day wide, which made it span yesterday as well — a screen headed today
		// showing roughly double the real figure.
		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-08-26"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-08-26"));
	}

	@Test
	void thisWeekStartsOnMonday() {
		// 2026-08-26 is a Wednesday.
		assertThat(on("2026-08-26", "week").from()).isEqualTo(LocalDate.parse("2026-08-24"));
	}

	@Test
	void thisWeekOnAMondayIsOneDayAndNotSeven() {
		// The boundary that catches an off-by-one in `previousOrSame`: `previous` would jump back a
		// whole week here and silently include all of last week under a "this week" heading.
		DateWindow window = on("2026-08-24", "week");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-08-24"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-08-24"));
	}

	@Test
	void thisWeekOnASundayIsTheWholeWeekAndNotOneDay() {
		// 2026-08-30 is a Sunday. Under ISO-8601 that is the LAST day of the week beginning
		// Monday 24th — not the first day of a new one. A Sunday-start implementation gives
		// from == to here, so this case is what actually distinguishes the two conventions.
		DateWindow window = on("2026-08-30", "week");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-08-24"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-08-30"));
	}

	@Test
	void thisMonthOnTheFirstIsASingleDay() {
		DateWindow window = on("2026-08-01", "month");

		// A one-day-wide "this month" is correct and looks broken, which is why it is asserted:
		// somebody will read a near-empty dashboard on the 1st and file a bug.
		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-08-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-08-01"));
	}

	@Test
	void thisYearOnNewYearsDayIsASingleDay() {
		DateWindow window = on("2026-01-01", "year");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-01-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-01-01"));
	}

	@Test
	void lastMonthIsTheWholePreviousMonthAndDoesNotEndToday() {
		DateWindow window = on("2026-08-26", "last-month");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-07-01"));
		// **The only named range whose `to` is in the past.** A resolver that derived `to` from the
		// current instant — which the previous code did — cannot express this window at all.
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-07-31"));
	}

	@Test
	void lastMonthFromTheThirtyFirstDoesNotSkipAMonth() {
		// The case that breaks the wrong order of operations. On 31 March, `minusMonths(1)` clamps
		// to 28 February and lands in February. Take the 1st of the month FIRST and you get
		// 1 March, minus a month is 1 February — the same answer here, which is what makes this
		// look safe. On 31 May the two orders diverge: February vs April.
		DateWindow march = on("2026-03-31", "last-month");
		assertThat(march.from()).isEqualTo(LocalDate.parse("2026-02-01"));
		assertThat(march.to()).isEqualTo(LocalDate.parse("2026-02-28"));

		DateWindow may = on("2026-05-31", "last-month");
		assertThat(may.from()).isEqualTo(LocalDate.parse("2026-04-01"));
		assertThat(may.to()).isEqualTo(LocalDate.parse("2026-04-30"));
	}

	@Test
	void lastMonthInJanuaryIsDecemberOfThePreviousYear() {
		DateWindow window = on("2026-01-15", "last-month");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2025-12-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2025-12-31"));
	}

	@Test
	void lastMonthPicksUpTheLeapDay() {
		DateWindow window = on("2028-03-10", "last-month");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2028-02-01"));
		// 2028 is a leap year. A hard-coded 28 here would silently drop a day of deals.
		assertThat(window.to()).isEqualTo(LocalDate.parse("2028-02-29"));
	}

	@Test
	void lastYearIsTheWholePreviousCalendarYear() {
		DateWindow window = on("2026-08-26", "last-year");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2025-01-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2025-12-31"));
	}

	@Test
	void lastYearFromALeapDayStillCoversAWholeYear() {
		// 29 February 2028 minus a year is 28 February 2027, which must still widen to the whole
		// of 2027 rather than to a window ending in February.
		DateWindow window = on("2028-02-29", "last-year");

		assertThat(window.from()).isEqualTo(LocalDate.parse("2027-01-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2027-12-31"));
	}

	@Test
	void aCustomWindowIsExactlyWhatTheCallerAsked() {
		DateWindow window = DateWindow.of("custom", "2026-01-01", "2026-03-31", fixed("2026-08-26"));

		assertThat(window.range()).isEqualTo(DateRange.CUSTOM);
		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-01-01"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-03-31"));
	}

	@Test
	void aSingleDayCustomWindowIsAllowed() {
		// Equal edges are a legitimate question, and refusing them would make the picker unable to
		// express the one window `today` already can.
		DateWindow window = DateWindow.of("custom", "2026-02-14", "2026-02-14", fixed("2026-08-26"));

		assertThat(window.from()).isEqualTo(window.to());
	}

	@Test
	void aCustomWindowMayEndInTheFutureBecauseThatIsMerelyEmpty() {
		// Not refused: a caller asking to next month gets no rows, which is a true answer. Refusing
		// it would be this class inventing a rule about what is worth asking.
		assertThat(DateWindow.of("custom", "2026-08-01", "2027-01-01", fixed("2026-08-26")).to())
				.isEqualTo(LocalDate.parse("2027-01-01"));
	}

	@Test
	void aCustomWindowWithoutBothEdgesIsRefused() {
		for (String[] edges : new String[][] { { null, "2026-03-31" }, { "2026-01-01", null },
				{ null, null }, { "  ", "2026-03-31" } }) {
			assertThatThrownBy(() -> DateWindow.of("custom", edges[0], edges[1], fixed("2026-08-26")))
					.describedAs("from=%s to=%s", edges[0], edges[1])
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("needs both from and to");
		}
	}

	@Test
	void aCustomWindowThatEndsBeforeItStartsIsRefused() {
		assertThatThrownBy(() -> DateWindow.of("custom", "2026-03-31", "2026-01-01", fixed("2026-08-26")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("is after");
	}

	@Test
	void anUnparseableCustomEdgeNamesTheFieldAndTheExpectedShape() {
		assertThatThrownBy(() -> DateWindow.of("custom", "31/03/2026", "2026-04-01", fixed("2026-08-26")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("from")
				.hasMessageContaining("yyyy-MM-dd");
	}

	/**
	 * Dates on a named range are <strong>refused, not ignored</strong>.
	 *
	 * <p>The behaviour worth a test rather than a comment: a caller who writes
	 * {@code ?range=month&from=2026-01-01} means January, and answering for this month instead is a
	 * wrong number wearing a right-looking label. Same call {@link DateRange#parse} makes about an
	 * unknown range name.
	 */
	@Test
	void explicitDatesOnANamedRangeAreRefusedRatherThanIgnored() {
		assertThatThrownBy(() -> DateWindow.of("month", "2026-01-01", "2026-01-31", fixed("2026-08-26")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("only accepted with range=custom");
	}

	@Test
	void anUnknownRangeNamesEveryOptionItWouldHaveAccepted() {
		assertThatThrownBy(() -> DateWindow.of("fortnight", null, null, fixed("2026-08-26")))
				.isInstanceOf(InvalidRequestException.class)
				// Built from the enum, so a range added without appearing here is impossible.
				.hasMessageContaining("last-month")
				.hasMessageContaining("custom");
	}

	@Test
	void theWireNamesAreHyphenatedAndCaseInsensitiveOnTheWayIn() {
		assertThat(DateRange.LAST_MONTH.wireName()).isEqualTo("last-month");
		assertThat(DateRange.parse("LAST-MONTH")).isEqualTo(DateRange.LAST_MONTH);
		assertThat(DateRange.parse(" last-year ")).isEqualTo(DateRange.LAST_YEAR);
	}

	@Test
	void theInstantBoundsAreTheWholeDaysInThisWindowsOwnZone() {
		DateWindow window = on("2026-08-26", "today");

		// Midnight LA opening the day...
		assertThat(window.startInstant()).isEqualTo(LocalDate.parse("2026-08-26").atStartOfDay(LA).toInstant());
		// ...and midnight LA opening the NEXT day, exclusive. An inclusive end would have to be the
		// last representable instant, and anything stored with finer precision than that bound
		// falls outside the window — a row silently dropped from the day it happened on.
		assertThat(window.endInstant()).isEqualTo(LocalDate.parse("2026-08-27").atStartOfDay(LA).toInstant());
	}

	@Test
	void zoneCrossingIsResolvedInTheBusinessZoneAndNotUtc() {
		// 22:00 LA on 26 August is already 05:00 UTC on the 27th. "Today" must still be the 26th:
		// this is the business's day, the same one every SLA in the app is measured on.
		Clock lateEvening = Clock.fixed(Instant.parse("2026-08-27T05:00:00Z"), LA);

		DateWindow window = DateWindow.of("today", null, null, lateEvening);

		assertThat(window.from()).isEqualTo(LocalDate.parse("2026-08-26"));
		assertThat(window.to()).isEqualTo(LocalDate.parse("2026-08-26"));
	}

	/**
	 * The cache key is the resolved days, and <strong>this is the assertion the cache correctness
	 * rests on</strong>.
	 *
	 * <p>Two different custom windows are both named {@code custom}. Keyed by range name they
	 * would collide in {@code ghl_funnel_cache} and serve each other's figures for a whole TTL,
	 * with nothing on screen to contradict it because the payloads are identical in shape.
	 */
	@Test
	void twoDifferentCustomWindowsHaveDifferentKeys() {
		DateWindow january = DateWindow.of("custom", "2026-01-01", "2026-01-31", fixed("2026-08-26"));
		DateWindow march = DateWindow.of("custom", "2026-03-01", "2026-03-31", fixed("2026-08-26"));

		assertThat(january.key()).isEqualTo("2026-01-01..2026-01-31");
		assertThat(january.key()).isNotEqualTo(march.key());
	}

	@Test
	void aNamedRangesKeyChangesWithTheDaySoAStaleRowIsAMissRatherThanAWrongAnswer() {
		// The smaller fault window-keying fixes for free: a row written for `month` on the 25th used
		// to keep answering for `month` on the 26th, when "this month" had become a wider window.
		assertThat(on("2026-08-25", "month").key()).isNotEqualTo(on("2026-08-26", "month").key());
	}

	@Test
	void aNamedRangeAndAnIdenticalCustomWindowShareAKeyOnPurpose() {
		// Not a collision — the same days ARE the same question, so they should share the cached
		// answer. Only the payload's `range` label differs, and that is display, not identity.
		assertThat(on("2026-08-26", "today").key())
				.isEqualTo(DateWindow.of("custom", "2026-08-26", "2026-08-26", fixed("2026-08-26")).key());
	}
}
