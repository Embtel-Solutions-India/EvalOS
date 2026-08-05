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
	EXCEPTION_RAISED
}
