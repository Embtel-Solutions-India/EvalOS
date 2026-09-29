package com.ie.evalos.service;

import java.math.BigDecimal;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;

/**
 * What a new offer pays (Unit 65 rules 1–2). Pure, so the three transitions that open an offer
 * share one rule and a test can pin it without a case.
 *
 * <p>{@code base} is the expert's standard fee, or — on a retake — the fee of the offer the expert
 * declined. A blank request means the base. A Case Manager may only offer at the base.
 */
final class OfferFees {

	private OfferFees() {
	}

	static BigDecimal price(Role role, BigDecimal requested, BigDecimal base) {
		if (role == Role.CASE_MANAGER) {
			if (requested != null && (base == null || requested.compareTo(base) != 0)) {
				throw new ForbiddenException("A case manager offers at the expert's standard fee.");
			}
			if (base == null) {
				throw new IllegalTransitionException("Ask a PM, PC or ENM to set the fee for this case.");
			}
			return base;
		}
		BigDecimal fee = requested != null ? requested : base;
		if (fee == null) {
			throw new IllegalTransitionException("Set a fee for this case — this expert has no standard fee.");
		}
		return fee;
	}
}
