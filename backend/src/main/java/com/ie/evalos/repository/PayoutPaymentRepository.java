package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.service.ScopePredicate;

/**
 * Payments are brand-scoped only, exactly like the ledger rows they settle:
 * {@code recorded_by} names who sent the money, not who owns the row, so it is not an
 * assignee axis. No new scoping code — {@code brandOnly} already covers this.
 */
public interface PayoutPaymentRepository extends ScopedRepository<PayoutPayment> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** One expert's payment history, newest first. Call only with a scoped brand id. */
	List<PayoutPayment> findByBrandIdAndExpertIdOrderByPaidDateDesc(UUID brandId, UUID expertId);
}
