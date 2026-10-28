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

	/** Spec 86: the base is what GHL last held, so a second edit before the push keeps the first base. */
	@Test
	void theBaseIsTheValueBeforeTheFirstUnconfirmedEdit() {
		Opportunity row = new Opportunity(UUID.randomUUID(), "opp-1", UUID.randomUUID());
		row.syncFromGhl("c", row.getPipelineId(), "s", "Deal", new java.math.BigDecimal("500"), "open", null, null,
				null, null, null, null);

		row.editedLocally(null, new java.math.BigDecimal("150"), null, null);
		row.editedLocally(null, new java.math.BigDecimal("175"), null, null);

		assertThat(row.ghlBaseOf(FieldOwnership.AMOUNT)).isEqualTo("500");
		assertThat(row.ghlBaseOf(FieldOwnership.NAME)).isNull();
	}

	/** GHL's answer that supersedes the edit retires the base with it. */
	@Test
	void aSyncThatSupersedesTheEditClearsTheBase() {
		Opportunity row = new Opportunity(UUID.randomUUID(), "opp-1", UUID.randomUUID());
		row.syncFromGhl("c", row.getPipelineId(), "s", "Deal", new java.math.BigDecimal("500"), "open", null, null,
				null, null, null, null);
		row.editedLocally(null, new java.math.BigDecimal("150"), null, null);

		row.syncFromGhl("c", row.getPipelineId(), "s", "Deal", new java.math.BigDecimal("200"), "open", null, null,
				null, Instant.now().plusSeconds(60), null, null);

		assertThat(row.ghlBaseOf(FieldOwnership.AMOUNT)).isNull();
		assertThat(row.getAmount()).isEqualByComparingTo("200");
	}
}
