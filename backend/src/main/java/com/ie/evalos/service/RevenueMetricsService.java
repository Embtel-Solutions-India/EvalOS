package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.BrandRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GM's and Brand Manager's money figures, per {@code 17-dashboards.md} §1.
 *
 * <p><strong>"Refunded" is not a column.</strong> It is a derived pair — stage {@code CLOSED} plus
 * {@code exception_state = REFUND_REQUESTED} — and {@link RefundService} owns both expressions
 * that matter. This service <em>imports</em> {@code RefundService.isRefunded} and
 * {@code isRevenueRecognized} rather than re-expressing them: two definitions of "refunded" is how
 * a revenue tile and a case's own state come to disagree about the same money.
 *
 * <p>Unit 17's table writes {@code where paid AND refunded} as though the column existed; it is
 * corrected there rather than worked around here.
 */
@Service
public class RevenueMetricsService {

	private final CaseLifecycleService lifecycle;
	private final BrandRepository brands;

	RevenueMetricsService(CaseLifecycleService lifecycle, BrandRepository brands) {
		this.lifecycle = lifecycle;
		this.brands = brands;
	}

	/**
	 * @param collected     paid and not refunded
	 * @param recognized    paid, delivered, not refunded — invariant 5
	 * @param openLiability paid but not delivered: money taken for work not yet done, i.e. refund
	 *                      exposure. **This is the figure the GM's screen exists for.**
	 * @param refunded      shown beside the three and inside none of them
	 * @param reconciles    whether {@code collected == recognized + openLiability}. Stated rather
	 *                      than assumed, so three numbers that quietly stop adding up say so
	 *                      instead of being read as fact.
	 */
	public record Money(BigDecimal collected, BigDecimal recognized, BigDecimal openLiability,
			BigDecimal refunded, boolean reconciles) {
	}

	public record BrandMoney(UUID brandId, String name, Money money, int cases) {
	}

	/**
	 * @param perBrand populated for a cross-brand caller (the GM with no brand filter) and empty
	 *                 otherwise. A single-brand reader does not need the same number twice.
	 */
	public record RevenueMetrics(Money total, List<BrandMoney> perBrand, int openCases) {
	}

	@Transactional(readOnly = true)
	public RevenueMetrics forCaller(UUID brandId) {
		List<Case> scoped = lifecycle.list(null, null, null).stream()
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();

		Map<UUID, List<Case>> byBrand = new HashMap<>();
		for (Case subject : scoped) {
			byBrand.computeIfAbsent(subject.getBrandId(), key -> new ArrayList<>()).add(subject);
		}

		List<BrandMoney> perBrand = List.of();
		if (byBrand.size() > 1) {
			Map<UUID, String> names = new HashMap<>();
			brands.findAll().forEach(brand -> names.put(brand.getId(), brand.getName()));
			perBrand = byBrand.entrySet().stream()
					.map(entry -> new BrandMoney(entry.getKey(),
							names.getOrDefault(entry.getKey(), "Unknown brand"),
							money(entry.getValue()), entry.getValue().size()))
					.sorted(Comparator.comparing(BrandMoney::name))
					.toList();
		}

		return new RevenueMetrics(money(scoped), perBrand, openCases(scoped));
	}

	/**
	 * How many cases the open-liability figure is made of.
	 *
	 * <p><strong>Filtered exactly as {@code openLiability} is</strong> — paid, not refunded, not
	 * delivered — and that is the whole point of it being its own method. It previously counted
	 * every scoped case with no delivery date, so the tile read "£X across N cases" with the money
	 * and the count taken over different populations: an unpaid or refunded case inflated N while
	 * contributing nothing to X.
	 */
	private static int openCases(List<Case> cases) {
		return (int) cases.stream()
				.filter(subject -> subject.isPaid() && subject.getDealValue() != null)
				.filter(subject -> !RefundService.isRefunded(subject))
				.filter(subject -> subject.getDeliveryDate() == null)
				.count();
	}

	private static Money money(List<Case> cases) {
		BigDecimal collected = BigDecimal.ZERO;
		BigDecimal recognized = BigDecimal.ZERO;
		BigDecimal open = BigDecimal.ZERO;
		BigDecimal refunded = BigDecimal.ZERO;

		for (Case subject : cases) {
			BigDecimal value = subject.getDealValue();
			if (!subject.isPaid() || value == null) {
				continue;
			}
			if (RefundService.isRefunded(subject)) {
				refunded = refunded.add(value);
				continue;
			}
			collected = collected.add(value);
			if (subject.getDeliveryDate() != null) {
				recognized = recognized.add(value);
			} else {
				open = open.add(value);
			}
		}
		// Collected excludes refunds and the two below partition it, so this holds exactly. It is
		// asserted rather than trusted: if it ever fails, the screen says so.
		return new Money(collected, recognized, open, refunded,
				collected.compareTo(recognized.add(open)) == 0);
	}
}
