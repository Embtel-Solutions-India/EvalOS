package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A desk's status change dates itself, so a win counts in the period it was made before GHL confirms it. */
class OpportunityLocalEditTest {

	@Test
	void aLocalStatusChangeStampsWhenItHappenedAndARepeatDoesNot() {
		Opportunity row = new Opportunity(UUID.randomUUID(), "opp-1", UUID.randomUUID());
		Instant march = Instant.parse("2026-03-01T00:00:00Z");
		row.syncFromGhl("c", row.getPipelineId(), "s", "Deal", null, "lost", null, null, march, march, march, march);

		Instant before = Instant.now();
		row.editedLocally(null, null, null, "won");
		Instant stamped = row.getLastStatusChangeAt();
		assertThat(stamped).isAfterOrEqualTo(before);

		row.editedLocally(null, null, null, "won");
		assertThat(row.getLastStatusChangeAt()).isEqualTo(stamped);
	}
}
