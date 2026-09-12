package com.ie.evalos.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.service.ScopePredicate;

/**
 * Payouts are brand-scoped only. {@code recorded_by} names who entered the row,
 * not who it belongs to, so it is not an assignee axis.
 */
public interface PayoutLedgerRepository extends ScopedRepository<PayoutLedger> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The rows a refund has to void. Not scoped on its own: only call it with a case
	 * id that came back from {@code CaseRepository.findScoped}.
	 */
	List<PayoutLedger> findByCaseIdAndStatus(UUID caseId, PayoutStatus status);

	/**
	 * Every ledger row for one expert in one brand, newest first — the expert's own read of their
	 * payouts (Unit 35, D6). Covered by {@code V8}'s {@code idx_payout_brand_expert}.
	 *
	 * <p>Brand-scoped in the signature rather than through {@code findScoped}: the caller is a
	 * portal token, which has no {@code TenantContext}, and the brand comes off the credential
	 * itself. An expert on two brands' panels holds two tokens and sees two ledgers, which is the
	 * correct answer — a payout is a brand's obligation, not the platform's.
	 */
	List<PayoutLedger> findByBrandIdAndExpertIdOrderByCreatedAtDesc(UUID brandId, UUID expertId);

	/** One expert's pending total. Derived; {@code expert.total_payments_pending} stays dead. */
	interface ExpertPendingTotal {

		UUID getExpertId();

		BigDecimal getTotal();
	}

	/**
	 * What every expert on a brand is owed, in one query rather than one per row.
	 *
	 * <p>{@code Expert.total_payments_pending} is {@code NOT NULL DEFAULT 0} and nothing
	 * has ever written it. A running total maintained by hand has to be adjusted on
	 * create, on settle, on void and on every amount correction — four chances to drift
	 * on a figure about money. Unit 11 set this rule for the same situation.
	 */
	@Query("""
			select p.expertId as expertId, sum(p.amount) as total
			  from PayoutLedger p
			 where p.brandId = :brandId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PENDING
			   and p.amount is not null
			 group by p.expertId
			""")
	List<ExpertPendingTotal> pendingTotalsByExpert(@Param("brandId") UUID brandId);

	/**
	 * Attach rows to a payment and mark them PAID — <b>one conditional statement, not a
	 * read followed by saves.</b>
	 *
	 * <p>Checking each row is {@code PENDING} and then writing it is a check-then-act
	 * that two concurrent settlements can both win, the same shape
	 * {@code uq_payout_per_case} exists for. Here the guard covers a <em>set</em> of rows,
	 * which a unique index has nothing to say about, so the precondition rides in the
	 * {@code WHERE} clause and the caller asserts the affected count. The database
	 * decides once and cannot decide twice.
	 *
	 * @return how many rows were actually taken; anything less than the requested count
	 *         means another transaction won one, and the caller must roll the whole
	 *         settlement back
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update PayoutLedger p
			   set p.paymentId = :paymentId,
			       p.status = com.ie.evalos.domain.PayoutStatus.PAID,
			       p.recordedBy = :actor
			 where p.id in :ids
			   and p.brandId = :brandId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PENDING
			""")
	int attachToPayment(@Param("paymentId") UUID paymentId, @Param("ids") Collection<UUID> ids,
			@Param("brandId") UUID brandId, @Param("actor") UUID actor);

	/** One transfer, one acknowledgement: confirming a payment confirms everything it settled. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update PayoutLedger p
			   set p.status = com.ie.evalos.domain.PayoutStatus.CONFIRMED
			 where p.paymentId = :paymentId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PAID
			""")
	int confirmForPayment(@Param("paymentId") UUID paymentId);

	/** The drafts one payment settled. Call only with a payment id from a scoped read. */
	List<PayoutLedger> findByPaymentId(UUID paymentId);
}
