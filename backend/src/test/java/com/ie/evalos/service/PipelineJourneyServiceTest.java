package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The monthly arithmetic behind the Sales and Marketing dashboards; scoping is the controller's and the principal's. */
class PipelineJourneyServiceTest {

	private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

	private static PipelineJourneyService.Deal deal(String created, String status, String amount, String source,
			String stage, String changed) {
		return new PipelineJourneyService.Deal(Instant.parse(created), status, new BigDecimal(amount), source, stage,
				changed == null ? null : Instant.parse(changed));
	}

	@Test
	void bucketsLeadsByCreatedMonthInTheBusinessZoneAndWinsByCloseMonth() {
		var deals = List.of(
				// 03:00Z on 1 Feb is still 31 Jan in Los Angeles.
				deal("2026-02-01T03:00:00Z", "won", "100", "Website", "s1", "2026-03-10T12:00:00Z"),
				deal("2026-02-15T12:00:00Z", "open", "50", " website ", "s1", null),
				deal("2025-12-20T12:00:00Z", "open", "999", "Website", "s2", null));

		var result = PipelineJourneyService.aggregate(deals, 2026, ZONE, null);

		assertThat(result.months().get(0).leads()).isEqualTo(1);
		assertThat(result.months().get(1).leads()).isEqualTo(1);
		assertThat(result.months().get(2).won()).isEqualTo(1);
		assertThat(result.months().get(2).wonValue()).isEqualByComparingTo("100");
		assertThat(result.sources()).singleElement().satisfies((row) -> assertThat(row.leads()).isEqualTo(2));
	}

	@Test
	void sourceFilterNarrowsMonthsAndStagesButNotTheSourceList() {
		var deals = List.of(
				deal("2026-04-02T12:00:00Z", "open", "10", "Google Ads", "s1", null),
				deal("2026-04-03T12:00:00Z", "open", "20", null, "s1", null));

		var result = PipelineJourneyService.aggregate(deals, 2026, ZONE, "google ads");

		assertThat(result.months().get(3).leads()).isEqualTo(1);
		assertThat(result.stageCounts().get("s1")[0]).isEqualTo(1);
		assertThat(result.sources()).extracting(PipelineJourneyService.SourceRow::source)
				.containsExactlyInAnyOrder("Google Ads", "Unattributed");
	}

	@Test
	void stagesCountOnlyOpenDeals() {
		var deals = List.of(deal("2026-04-02T12:00:00Z", "lost", "10", "x", "s1", "2026-04-05T12:00:00Z"));
		assertThat(PipelineJourneyService.aggregate(deals, 2026, ZONE, null).stageCounts()).isEmpty();
	}
}
