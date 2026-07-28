package com.ie.evalos.domain;

/** Why a staff notification was raised. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum NotificationType {

	CASE_ASSIGNED,
	STAGE_CHANGED,
	SLA_AT_RISK,
	SLA_OVERDUE,
	EXCEPTION_RAISED
}
