package com.ie.evalos.domain;

/**
 * What became of an offer to an expert.
 *
 * <p>Closed for the same reasons as {@link FieldTag}, and enforced the same two ways
 * ({@code expert_case_offer_outcome_known} in V19). The match scorer divides by a count of
 * these values, so an unrecognised spelling would drop out of the numerator and the
 * denominator at once and quietly move an expert's acceptance rate.
 */
public enum OfferOutcome {

	/** Sent, unanswered. The only state a row is created in. */
	OFFERED,
	ACCEPTED,
	DECLINED,
	/**
	 * The expert never answered inside the signing SLA. Declared here, written by nobody
	 * until Unit 15's {@code EXPERT_TIMED_OUT} — a staff act, prompted by Unit 19's timer
	 * but not fired by it.
	 */
	TIMED_OUT,
	/** The case was rematched while this offer was still open, so it will never be answered. */
	SUPERSEDED;

	/**
	 * Whether this outcome says something about the expert's own behaviour, and so counts in
	 * the acceptance rate.
	 *
	 * <p>{@link #SUPERSEDED} deliberately does not: the expert was never given the chance to
	 * answer, and counting a withdrawal against them would penalise an expert for a
	 * reassignment somebody else made. {@link #OFFERED} does not either — it has not happened
	 * yet, and treating an unanswered offer as a decline would make an expert's rate fall
	 * while they are still deciding.
	 */
	public boolean countsTowardAcceptanceRate() {
		return this == ACCEPTED || this == DECLINED || this == TIMED_OUT;
	}
}
