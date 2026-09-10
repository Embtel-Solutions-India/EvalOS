package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.PortalAccessRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G16: is a clock running against a link nobody has opened?
 *
 * <p>The two tests that carry the weight are {@link #anExpertSigningCaseWithNoLiveLinkIsRed} —
 * the failure this tile exists for, since the 20h/24h signing clock runs whether or not the
 * expert ever received their link — and {@link #anAbsentLinkIsGreenWhereTheStageDoesNotWantOne},
 * which is what stops the tile crying wolf on every case and being ignored by the day it is
 * right.
 */
class PortalLinkLedgerServiceTest {

	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final PortalAccessRepository links = mock(PortalAccessRepository.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);
	private final PortalLinkLedgerService ledger = new PortalLinkLedgerService(lifecycle, links, sla);

	private static Case caseAt(Stage stage, ClientApprovalStatus approval) {
		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(CASE_ID);
		when(subject.getCaseCode()).thenReturn("IE-2026-0001");
		when(subject.getCurrentStage()).thenReturn(stage);
		when(subject.getClientApprovalStatus()).thenReturn(approval);
		return subject;
	}

	private static PortalAccess link(Instant expiresAt, Instant lastSeenAt, Instant revokedAt) {
		PortalAccess access = mock(PortalAccess.class);
		when(access.getExpiresAt()).thenReturn(expiresAt);
		when(access.getLastSeenAt()).thenReturn(lastSeenAt);
		when(access.getRevokedAt()).thenReturn(revokedAt);
		return access;
	}

	private void given(Case subject, SlaStatus stageSla, PortalAudience audience,
			List<PortalAccess> history) {
		when(lifecycle.list(any(), any(), any())).thenReturn(List.of(subject));
		when(sla.statusOf(subject)).thenReturn(stageSla);
		when(links.findByCaseIdAndAudienceOrderByCreatedAtDesc(CASE_ID, audience)).thenReturn(history);
		for (PortalAudience other : PortalAudience.values()) {
			if (other != audience) {
				when(links.findByCaseIdAndAudienceOrderByCreatedAtDesc(CASE_ID, other))
						.thenReturn(List.of());
			}
		}
	}

	private PortalLinkLedgerService.LedgerRow rowFor(PortalAudience audience) {
		return ledger.forCaller().stream()
				.filter((row) -> row.audience() == audience)
				.findFirst()
				.orElseThrow();
	}

	/**
	 * <strong>The failure this whole tile exists for.</strong>
	 *
	 * <p>An expert is at signing, the clock is running, and there is no live link — so nobody
	 * could have signed even if they wanted to. Nothing in EvalOS showed this before G16.
	 */
	@Test
	void anExpertSigningCaseWithNoLiveLinkIsRed() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT, List.of());

		PortalLinkLedgerService.LedgerRow row = rowFor(PortalAudience.EXPERT);

		assertThat(row.state()).isEqualTo(PortalLinkLedgerService.LinkState.RED);
		assertThat(row.live()).isFalse();
		assertThat(row.needed()).isTrue();
	}

	/** A revoked link is not a live one, however recently it was minted. */
	@Test
	void aRevokedLinkDoesNotCountAsLive() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT,
				List.of(link(Instant.now().plus(Duration.ofDays(5)), null, Instant.now())));

		assertThat(rowFor(PortalAudience.EXPERT).state())
				.isEqualTo(PortalLinkLedgerService.LinkState.RED);
	}

	/** An expired link is not a live one either. */
	@Test
	void anExpiredLinkDoesNotCountAsLive() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT,
				List.of(link(Instant.now().minus(Duration.ofHours(1)), null, null)));

		assertThat(rowFor(PortalAudience.EXPERT).live()).isFalse();
	}

	/**
	 * <strong>The one that keeps the tile worth looking at.</strong>
	 *
	 * <p>A case at drafting wants no expert link, so its absence is correct. A tile that showed
	 * red for every case not yet at signing would be ignored within a week — and then it would
	 * be ignored on the day it was right.
	 */
	@Test
	void anAbsentLinkIsGreenWhereTheStageDoesNotWantOne() {
		given(caseAt(Stage.DRAFT_IN_PROGRESS, null), SlaStatus.OVERDUE, PortalAudience.EXPERT,
				List.of());

		PortalLinkLedgerService.LedgerRow row = rowFor(PortalAudience.EXPERT);

		assertThat(row.state()).isEqualTo(PortalLinkLedgerService.LinkState.GREEN);
		assertThat(row.needed()).isFalse();
	}

	/** Never opened plus an at-risk stage is amber: the clock is close, not yet blown. */
	@Test
	void neverOpenedOnAnAtRiskStageIsAmber() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.AT_RISK, PortalAudience.EXPERT,
				List.of(link(Instant.now().plus(Duration.ofDays(5)), null, null)));

		assertThat(rowFor(PortalAudience.EXPERT).state())
				.isEqualTo(PortalLinkLedgerService.LinkState.AMBER);
	}

	/** Never opened and the stage is already overdue: red, because the clock has blown. */
	@Test
	void neverOpenedOnAnOverdueStageIsRed() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.OVERDUE, PortalAudience.EXPERT,
				List.of(link(Instant.now().plus(Duration.ofDays(5)), null, null)));

		assertThat(rowFor(PortalAudience.EXPERT).state())
				.isEqualTo(PortalLinkLedgerService.LinkState.RED);
	}

	/** Opened and comfortably in date is the state nobody needs to look at. */
	@Test
	void openedAndInDateIsGreen() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT,
				List.of(link(Instant.now().plus(Duration.ofDays(5)), Instant.now(), null)));

		PortalLinkLedgerService.LedgerRow row = rowFor(PortalAudience.EXPERT);

		assertThat(row.state()).isEqualTo(PortalLinkLedgerService.LinkState.GREEN);
		assertThat(row.openedAt()).isNotNull();
	}

	/** Opened but nearly dead, on a case still running: amber, because it needs re-minting. */
	@Test
	void openedButExpiringInsideTwoDaysIsAmber() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT,
				List.of(link(Instant.now().plus(Duration.ofHours(6)), Instant.now(), null)));

		assertThat(rowFor(PortalAudience.EXPERT).state())
				.isEqualTo(PortalLinkLedgerService.LinkState.AMBER);
	}

	/** A client link is wanted at review, and its absence there is red for the same reason. */
	@Test
	void aClientLinkIsNeededAtClientReview() {
		given(caseAt(Stage.CLIENT_REVIEW, null), SlaStatus.ON_TRACK, PortalAudience.CLIENT, List.of());

		PortalLinkLedgerService.LedgerRow row = rowFor(PortalAudience.CLIENT);

		assertThat(row.needed()).isTrue();
		assertThat(row.state()).isEqualTo(PortalLinkLedgerService.LinkState.RED);
	}

	/** At client approval it is only wanted while the client has not answered. */
	@Test
	void aClientLinkIsNotNeededOnceTheClientHasApproved() {
		given(caseAt(Stage.CLIENT_APPROVAL, ClientApprovalStatus.APPROVED), SlaStatus.OVERDUE,
				PortalAudience.CLIENT, List.of());

		assertThat(rowFor(PortalAudience.CLIENT).needed()).isFalse();
	}

	/**
	 * One row per (case, audience) pair, with a re-mint count — not one row per token.
	 *
	 * <p>A case whose client link has been re-sent five times is one line saying so, rather than
	 * six lines of noise burying the case that has none.
	 */
	@Test
	void supersededTokensBecomeACountNotExtraRows() {
		Instant live = Instant.now().plus(Duration.ofDays(3));
		given(caseAt(Stage.CLIENT_REVIEW, null), SlaStatus.ON_TRACK, PortalAudience.CLIENT,
				List.of(link(live, Instant.now(), null),
						link(live, null, Instant.now()),
						link(live, null, Instant.now())));

		List<PortalLinkLedgerService.LedgerRow> clientRows = ledger.forCaller().stream()
				.filter((row) -> row.audience() == PortalAudience.CLIENT)
				.toList();

		assertThat(clientRows).hasSize(1);
		assertThat(clientRows.get(0).reMints()).isEqualTo(2);
	}

	/** Delivered and closed cases are history: no clock can be running against them. */
	@Test
	void deliveredAndClosedCasesAreNotInTheLedger() {
		// Built BEFORE the stubbing, not inside its argument list: `caseAt` stubs, and calling a
		// stubbing method while an outer `when(...)` is still open is Mockito's
		// UnfinishedStubbingException. Easy to write, and the error names the wrong line.
		Case delivered = caseAt(Stage.DELIVERED, null);
		Case closed = caseAt(Stage.CLOSED, null);
		when(lifecycle.list(any(), any(), any())).thenReturn(List.of(delivered, closed));

		assertThat(ledger.forCaller()).isEmpty();
	}

	/** Worst first: a ledger nobody scrolls is a ledger that missed the one red row. */
	@Test
	void redRowsSortAboveTheRest() {
		Case subject = caseAt(Stage.EXPERT_SIGNING, null);
		given(subject, SlaStatus.ON_TRACK, PortalAudience.EXPERT, List.of());

		List<PortalLinkLedgerService.LedgerRow> rows = ledger.forCaller();

		assertThat(rows.get(0).state()).isEqualTo(PortalLinkLedgerService.LinkState.RED);
	}

	/** The summary counts what the rows say, so the number and the list cannot disagree. */
	@Test
	void theSummaryCountsAgreeWithTheRows() {
		given(caseAt(Stage.EXPERT_SIGNING, null), SlaStatus.ON_TRACK, PortalAudience.EXPERT, List.of());

		PortalLinkLedgerService.Summary summary = ledger.summaryForCaller();

		assertThat(summary.red()).isEqualTo(
				(int) summary.rows().stream()
						.filter((row) -> row.state() == PortalLinkLedgerService.LinkState.RED).count());
		assertThat(summary.red()).isEqualTo(1);
	}

	/**
	 * There is no {@code sent_at} anywhere in this, and there must not be.
	 *
	 * <p>EvalOS cannot observe a staff member pasting a URL into someone else's mail client. A
	 * column recording a fact the system cannot witness is worse than an absent one, because a
	 * dashboard would then report it as if it were true. {@code openedAt} is the honest proxy.
	 */
	@Test
	void theRowRecordsWhenALinkWasOpenedAndNeverWhenItWasSent() {
		assertThat(PortalLinkLedgerService.LedgerRow.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.contains("openedAt")
				.doesNotContain("sentAt", "sent", "mintedBy");
	}
}
