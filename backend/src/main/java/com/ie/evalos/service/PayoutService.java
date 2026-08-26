package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an expert is owed, and what was actually sent.
 *
 * <p><b>EvalOS never moves money.</b> Nothing here talks to a bank, a card processor or
 * a disbursement rail, and no credential is stored. This is a ledger: a record that
 * money was owed and later paid, and every rule below follows from that.
 */
@Service
public class PayoutService {

	private final PayoutLedgerRepository payouts;

	private final PayoutPaymentRepository payments;

	private final ExpertRepository experts;

	private final BrandRepository brands;

	private final AuditService audit;

	PayoutService(PayoutLedgerRepository payouts, PayoutPaymentRepository payments, ExpertRepository experts,
			BrandRepository brands, AuditService audit) {
		this.payouts = payouts;
		this.payments = payments;
		this.experts = experts;
		this.brands = brands;
		this.audit = audit;
	}

	/**
	 * Open the row a delivered case owes its expert.
	 *
	 * <p>Called from inside {@code deliverToClient}'s transaction, deliberately:
	 * delivered and owed are one fact, and a delivery that committed without its payout
	 * row would be a case nobody gets paid for.
	 *
	 * @return the row, or empty when the case has no expert — which the caller reports
	 *         rather than swallows
	 */
	@Transactional
	public Optional<PayoutLedger> openForDelivery(Case delivered) {
		UUID expertId = delivered.getExpertId();
		if (expertId == null) {
			// A case can only reach FINAL_DELIVERY through EXPERT_SIGNING, so this should
			// be impossible — which is exactly why it is reported rather than swallowed.
			return Optional.empty();
		}

		Brand brand = brands.findById(delivered.getBrandId())
				.orElseThrow(() -> new IllegalStateException(
						"Case " + delivered.getId() + " names a brand that does not exist"));
		if (brand.getCurrency() == null) {
			// Guessing USD is the one guess in this unit that spends real money.
			throw new IllegalStateException(
					"Brand " + delivered.getBrandId() + " has no configured currency; no payout can be opened");
		}

		// Scoped to the case's brand, not the caller's: this runs inside deliverToClient,
		// which has no TenantContext axis that means "the brand this expert must belong
		// to" the way a GM's cross-brand context would not. An expert id from another
		// brand — should be impossible, since no expert is shared across brands — is
		// simply absent here, the same way an out-of-scope row is absent everywhere else.
		BigDecimal standardFee = experts.findByIdAndBrandId(expertId, delivered.getBrandId())
				.map(Expert::getStandardFee).orElse(null);
		Instant dueDate = delivered.getDeliveryDate().plus(brand.getPayoutTermDays(), ChronoUnit.DAYS);

		PayoutLedger row = payouts.save(new PayoutLedger(delivered.getBrandId(), delivered.getId(), expertId,
				standardFee, brand.getCurrency(), dueDate));

		audit.recordEvent("PAYOUT", row.getId(), AuditAction.CREATED, null,
				null, Map.of("caseId", delivered.getId(), "expertId", expertId, "status", "PENDING"));
		return Optional.of(row);
	}
}
