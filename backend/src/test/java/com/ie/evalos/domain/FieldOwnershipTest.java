package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-field ownership — Unit 45, slice E.
 *
 * <p>The rule being pinned is the one {@code 45-sync-engine.md} §3.2 chose <em>instead of</em>
 * "EvalOS wins": a blanket rule reverts GHL's automations, which is the one thing GHL was kept for.
 * So the assignee must survive a sync that disagrees with it, and the four shared fields must
 * survive only while EvalOS is holding an edit GHL has not confirmed.
 */
class FieldOwnershipTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID PIPELINE = UUID.randomUUID();
	// **Relative to the real clock, not fixed instants, and that is not laziness.**
	// `touchedLocally()` stamps `Instant.now()` and no caller can inject a clock into an entity,
	// so a fixed literal is only on the right side of "now" until the wall clock passes it — the
	// exact time bomb `GmOverviewServiceTest` shipped once already. These two are defined by their
	// relationship to now, which is the thing the assertions actually depend on.
	private static final Instant EARLIER = Instant.now().minusSeconds(3600);
	private static final Instant LATER = Instant.now().plusSeconds(3600);

	private Opportunity deal() {
		Opportunity row = new Opportunity(BRAND, "opp-1", PIPELINE);
		row.syncFromGhl("ghl-c-1", PIPELINE, "stage-1", "Rao", new BigDecimal("800"), "open",
				"CRM UI", "user-1", EARLIER, EARLIER, null, null);
		return row;
	}

	private static void absorb(Opportunity row, String stage, String name, String status,
			String assignedTo, Instant ghlUpdatedAt) {
		row.syncFromGhl("ghl-c-1", PIPELINE, stage, name, new BigDecimal("900"), status, "CRM UI",
				assignedTo, EARLIER, ghlUpdatedAt, null, null);
	}

	@Test
	void theFourSharedFieldsAreSharedAndEverythingElseIsGhls() {
		assertThat(FieldOwnership.of(FieldOwnership.STAGE)).isEqualTo(FieldOwnership.SHARED);
		assertThat(FieldOwnership.of(FieldOwnership.STATUS)).isEqualTo(FieldOwnership.SHARED);
		assertThat(FieldOwnership.of(FieldOwnership.AMOUNT)).isEqualTo(FieldOwnership.SHARED);
		assertThat(FieldOwnership.of(FieldOwnership.NAME)).isEqualTo(FieldOwnership.SHARED);
		assertThat(FieldOwnership.of(FieldOwnership.ASSIGNED_TO)).isEqualTo(FieldOwnership.GHL);
		assertThat(FieldOwnership.of(FieldOwnership.PIPELINE)).isEqualTo(FieldOwnership.GHL);
		assertThat(FieldOwnership.of("opportunityNote")).isEqualTo(FieldOwnership.EVALOS);
		// A column nobody has classified follows GHL rather than starting to defend itself.
		assertThat(FieldOwnership.of("somethingGhlStartedSending")).isEqualTo(FieldOwnership.GHL);
		assertThat(FieldOwnership.of(null)).isEqualTo(FieldOwnership.GHL);
	}

	/**
	 * <strong>The reason this slice exists.</strong> A round-robin reassigning a deal is GHL doing
	 * its job; a mirror that argued with it would revert the automation, which is the blanket rule
	 * §3.2 rejects.
	 */
	@Test
	void theAssigneeIsGhlsEvenWhenEvalOsHoldsAnUnconfirmedEdit() {
		Opportunity row = deal();
		row.touchedLocally();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", null);

		assertThat(row.getGhlAssignedTo()).isEqualTo("user-2");
	}

	@Test
	void aRowWithNoLocalEditFollowsGhlOnEveryField() {
		Opportunity row = deal();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", LATER);

		assertThat(row.getGhlStageId()).isEqualTo("stage-9");
		assertThat(row.getName()).isEqualTo("Renamed in GHL");
		assertThat(row.getStatus()).isEqualTo("won");
		assertThat(row.getAmount()).isEqualByComparingTo("900");
	}

	@Test
	void anEvalOsEditNewerThanGhlsKeepsTheSharedFields() {
		Opportunity row = deal();
		row.touchedLocally();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", EARLIER);

		assertThat(row.getGhlStageId()).isEqualTo("stage-1");
		assertThat(row.getName()).isEqualTo("Rao");
		assertThat(row.getStatus()).isEqualTo("open");
		assertThat(row.getAmount()).isEqualByComparingTo("800");
	}

	/**
	 * {@code 00d} §6.2's correction: {@code ghl_updated_at} is GHL-supplied and nullable, so
	 * reading absence as "GHL is newer" would quietly discard an edit that has not reached it.
	 */
	@Test
	void aNullGhlTimestampIsAConflictAndNotGhlBeingNewer() {
		Opportunity row = deal();
		row.touchedLocally();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", null);

		assertThat(row.getName()).isEqualTo("Rao");
	}

	/**
	 * The other half of the null rule, and the one that stops it degrading into "EvalOS always
	 * wins": with no local edit to defend, a null timestamp changes nothing.
	 */
	@Test
	void aNullGhlTimestampWithNothingToDefendStillFollowsGhl() {
		Opportunity row = deal();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", null);

		assertThat(row.getName()).isEqualTo("Renamed in GHL");
	}

	/** Once GHL's answer supersedes the local edit there is nothing left to defend. */
	@Test
	void aGhlWinClearsTheUnconfirmedEditSoItIsNotDefendedForEver() {
		Opportunity row = deal();
		row.touchedLocally();

		absorb(row, "stage-9", "Renamed in GHL", "won", "user-2", LATER);
		assertThat(row.getLocalUpdatedAt()).isNull();

		// And the next answer, whatever its timestamp, is taken.
		absorb(row, "stage-10", "Renamed again", "lost", "user-3", null);
		assertThat(row.getName()).isEqualTo("Renamed again");
	}

	/**
	 * A portal-born row would otherwise out-rank GHL on its four shared fields for life: it is
	 * created with a local edit and GHL has never confirmed one.
	 */
	@Test
	void ghlAcknowledgingTheCreateConfirmsTheLocalEdit() {
		Opportunity opened = new Opportunity(BRAND, null, PIPELINE);
		opened.touchedLocally();

		opened.linkGhl("opp-new");

		assertThat(opened.getLocalUpdatedAt()).isNull();
	}
}
