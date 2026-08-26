package com.ie.evalos.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two window shapes this enum owns, and why they are not the same subtraction.
 *
 * <p><strong>Written for a bug that shipped.</strong> {@code MarketingPipelineService} took
 * {@link DateRange#startFrom} — a half-open <em>instant</em> window, correct for the metrics
 * dashboards — converted it to a {@code LocalDate}, and handed it to GHL, whose filter is date-only
 * with <em>both edges inclusive</em>. Every window came out a day too wide, and {@code today}
 * covered yesterday as well: a screen headed "today" showing about double GHL's own figure.
 */
class DateRangeTest {

	/** The inclusive form: a window of N days spans exactly N days. */
	@Test
	void startDateFromSpansExactlyTheNamedNumberOfDays() {
		LocalDate to = LocalDate.of(2026, 8, 26);

		// The one that mattered: `today` is today, and nothing else.
		assertThat(DateRange.TODAY.startDateFrom(to)).isEqualTo(to);
		assertThat(DateRange.WEEK.startDateFrom(to)).isEqualTo(LocalDate.of(2026, 8, 20));
		assertThat(DateRange.MONTH.startDateFrom(to)).isEqualTo(LocalDate.of(2026, 7, 28));
		assertThat(DateRange.YEAR.startDateFrom(to)).isEqualTo(LocalDate.of(2025, 8, 27));

		// The same thing as the rule rather than four dates to trust: a window is as many days
		// wide as it is named, counting both ends.
		assertThat(ChronoUnit.DAYS.between(DateRange.WEEK.startDateFrom(to), to) + 1).isEqualTo(7);
		assertThat(ChronoUnit.DAYS.between(DateRange.YEAR.startDateFrom(to), to) + 1).isEqualTo(365);
	}

	/**
	 * {@code startFrom} is deliberately one day "wider" than {@code startDateFrom}, because it is
	 * half-open: the last 24 hours, not today. Pinned so nobody collapses the two methods into one.
	 */
	@Test
	void startFromIsHalfOpenAndStaysThatWay() {
		Instant to = Instant.parse("2026-08-26T12:00:00Z");

		assertThat(DateRange.TODAY.startFrom(to)).isEqualTo(Instant.parse("2026-08-25T12:00:00Z"));
		assertThat(DateRange.YEAR.startFrom(to)).isEqualTo(Instant.parse("2025-08-26T12:00:00Z"));
	}

	@Test
	void parseIsCaseInsensitiveAndRefusesAnythingElse() {
		assertThat(DateRange.parse("TODAY")).isEqualTo(DateRange.TODAY);
		assertThat(DateRange.parse("year")).isEqualTo(DateRange.YEAR);

		// Refused rather than defaulted: answering for a month when a year was asked for is a wrong
		// number that looks right.
		assertThatThrownBy(() -> DateRange.parse("quarter")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> DateRange.parse("")).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void wireNameIsWhatTheFrontendSends() {
		assertThat(DateRange.TODAY.wireName()).isEqualTo("today");
		assertThat(DateRange.YEAR.wireName()).isEqualTo("year");
	}
}
