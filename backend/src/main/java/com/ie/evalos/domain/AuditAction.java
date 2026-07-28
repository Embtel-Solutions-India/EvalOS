package com.ie.evalos.domain;

/** What an audit row records. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum AuditAction {

	CREATED,
	UPDATED,
	STAGE_CHANGED,
	ASSIGNED
}
