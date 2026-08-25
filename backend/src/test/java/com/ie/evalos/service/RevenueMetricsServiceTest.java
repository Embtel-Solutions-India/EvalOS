package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.BrandRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The money figures, and specifically the two ways they can quietly stop meaning what they say:
 * a refund counted as revenue, and three tiles that no longer add up.
 */
class RevenueMetricsServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final BigDecimal FEE = new BigDecimal("1000.00");

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final RevenueMetricsService revenue = new RevenueMetricsService(lifecycle, brands);

	private static Case paid(String code, Stage stage) {
		Case subject = new Case(BRAND, code, stage);
		subject.setPaid(true);
		subject.setPaidAt(Instant.now());
		subject.setDealValue(FEE);
		return subject;
	}

	private RevenueMetricsService.Money money(List<Case> cases) {
		given(lifecycle.list(any(), any(), any())).willReturn(cases);
		return revenue.forCaller(null).total();
	}

	@Test
	void collectedSplitsExactlyIntoRecognisedAndOpenLiability() {
		Case delivered = paid("IE-1", Stage.CLOSED);
		delivered.setDeliveryDate(Instant.now());
		Case inFlight = paid("IE-2", Stage.DRAFT_GENERATION);

		var total = money(List.of(delivered, inFlight));

		assertThat(total.collected()).isEqualByComparingTo("2000.00");
		assertThat(total.recognized()).isEqualByComparingTo("1000.00");
		assertThat(total.openLiability()).isEqualByComparingTo("1000.00");
		assertThat(total.reconciles())
				.as("collected must equal recognised + open liability, or the tiles disagree")
				.isTrue();
	}

	/**
	 * Invariant 5: a GM-approved refund reverses recognition. The refunded case must appear in
	 * exactly one figure — its own — and in none of the other three.
	 *
	 * <p>**Refunded is not a column.** It is `CLOSED` plus a held `REFUND_REQUESTED`, which is
	 * what `RefundService.isRefunded` expresses and what this asserts by building that pair.
	 */
	@Test
	void aRefundedCaseLeavesCollectedEntirelyRatherThanSittingInIt() {
		Case refunded = paid("IE-1", Stage.CLOSED);
		refunded.setExceptionState(ExceptionState.REFUND_REQUESTED);
		refunded.setDeliveryDate(Instant.now());

		var total = money(List.of(refunded));

		assertThat(total.refunded()).isEqualByComparingTo("1000.00");
		assertThat(total.collected()).isEqualByComparingTo("0.00");
		assertThat(total.recognized()).isEqualByComparingTo("0.00");
		assertThat(total.openLiability()).isEqualByComparingTo("0.00");
		assertThat(total.reconciles()).isTrue();
	}

	/** Since Case Creation v2.0 every case is born paid, but the guard costs nothing and holds. */
	@Test
	void anUnpaidCaseContributesToNothing() {
		Case unpaid = new Case(BRAND, "IE-1", Stage.DOC_COLLECTION);
		unpaid.setDealValue(FEE);

		assertThat(money(List.of(unpaid)).collected()).isEqualByComparingTo("0.00");
	}

	/**
	 * A case with no deal value cannot be added up, and treating a null as zero would silently
	 * understate collected revenue rather than saying anything was missing.
	 */
	@Test
	void aCaseWithNoDealValueIsSkippedRatherThanCountedAsZero() {
		Case valueless = new Case(BRAND, "IE-1", Stage.CLOSED);
		valueless.setPaid(true);
		valueless.setDeliveryDate(Instant.now());

		var total = money(List.of(valueless));

		assertThat(total.collected()).isEqualByComparingTo("0.00");
		assertThat(total.reconciles()).isTrue();
	}

	/**
	 * The count under the open-liability figure must be taken over the same population as the
	 * money above it. It was not: it counted every scoped case with no delivery date, so the tile
	 * read "£X across N cases" with X and N disagreeing about which cases they meant.
	 */
	@Test
	void theOpenCaseCountMatchesTheOpenLiabilityFigureItSitsUnder() {
		Case open = paid("IE-1", Stage.DRAFT_GENERATION);

		Case refundedUndelivered = paid("IE-2", Stage.CLOSED);
		refundedUndelivered.setExceptionState(ExceptionState.REFUND_REQUESTED);

		Case unpaid = new Case(BRAND, "IE-3", Stage.DRAFT_GENERATION);
		unpaid.setDealValue(FEE);

		given(lifecycle.list(any(), any(), any()))
				.willReturn(List.of(open, refundedUndelivered, unpaid));
		var metrics = revenue.forCaller(null);

		assertThat(metrics.total().openLiability()).isEqualByComparingTo("1000.00");
		assertThat(metrics.openCases())
				.as("only the one case that actually carries open liability")
				.isEqualTo(1);
	}

	/** A single-brand reader gets no per-brand breakdown: the same number twice is noise. */
	@Test
	void thePerBrandBreakdownIsOnlyBuiltWhenMoreThanOneBrandIsInScope() {
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(paid("IE-1", Stage.CLOSED)));

		assertThat(revenue.forCaller(null).perBrand()).isEmpty();
	}
}
