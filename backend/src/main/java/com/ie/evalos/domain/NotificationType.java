package com.ie.evalos.domain;

/** Why a staff notification was raised. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum NotificationType {

	/** A paid case landed in the brand pool and needs a project manager (Handoff A). */
	NEW_CASE_IN_POOL,
	CASE_ASSIGNED,
	STAGE_CHANGED,
	SLA_AT_RISK,
	SLA_OVERDUE,
	EXCEPTION_RAISED
}
