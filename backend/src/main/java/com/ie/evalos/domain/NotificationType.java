package com.ie.evalos.domain;

/** Why a staff notification was raised. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum NotificationType {

	/**
	 * <strong>No longer raised.</strong> It meant "a contact came in from GHL and became a case,
	 * not yet paid" — a state Case Creation v2.0 removed, since a case now exists only once the
	 * opportunity is Won and the money is in. Kept as a constant because it is persisted as text
	 * on notification rows already written; do not reuse it for something else.
	 */
	NEW_LEAD,
	/** A paid case has arrived in the pool and needs a project manager. */
	NEW_CASE_IN_POOL,
	CASE_ASSIGNED,
	STAGE_CHANGED,
	SLA_AT_RISK,
	SLA_OVERDUE,
	EXCEPTION_RAISED,
	/**
	 * A document chase the sweep could not send (Unit 19) — so it asks a Coordinator to.
	 *
	 * <p>Raised twice per case by design, at 24h and 48h, which is why nothing guards it with
	 * {@code alreadyRaised}: the {@code CHASED} audit rows are what cap it at two.
	 */
	DOC_CHASE_DUE,
	/**
	 * Documents still outstanding past the collection budget (Unit 19).
	 *
	 * <p><strong>Its own value rather than {@code SLA_OVERDUE}, and that is load-bearing.</strong>
	 * {@code StageSlaSweep} raises {@code SLA_OVERDUE} on the same case at the same moment — the
	 * two ask {@code SlaCalculator} the same question — so sharing the value would let whichever
	 * swept first suppress the other through {@code alreadyRaised}, and which message a PM got
	 * would depend on scheduler order.
	 */
	DOCS_ESCALATED,
	/**
	 * The expert is close to the signing deadline (Unit 19).
	 *
	 * <p>Separate from {@code SLA_AT_RISK} for the reason above: the stage sweep owns that value
	 * and would race this one for it.
	 */
	EXPERT_SIGN_AT_RISK,
	/**
	 * The signing deadline has passed and a human must decide (Unit 19).
	 *
	 * <p>Separate from {@code EXCEPTION_RAISED} because that value is raised on a case by four
	 * other paths — a refund request, a CM's flag, an expert declining. Any one of them would
	 * silence this prompt for the life of the case.
	 */
	EXPERT_SIGN_OVERDUE
}
