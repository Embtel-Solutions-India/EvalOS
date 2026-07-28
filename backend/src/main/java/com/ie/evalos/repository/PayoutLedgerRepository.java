package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

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
}
