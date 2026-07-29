package com.ie.evalos.domain;

/** Why a staff notification was raised. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum NotificationType {

	/** A contact came in from GHL and became a case, not yet paid (Handoff A). */
	NEW_LEAD,
	/** A case has been paid and now needs a project manager. */
	NEW_CASE_IN_POOL,
	CASE_ASSIGNED,
	STAGE_CHANGED,
	SLA_AT_RISK,
	SLA_OVERDUE,
	EXCEPTION_RAISED
}
