package com.ie.evalos.domain;

/** What an audit row records. Open vocabulary: the column carries no CHECK, so later units add values here without a migration. */
public enum AuditAction {

	CREATED,
	UPDATED,
	STAGE_CHANGED,
	ASSIGNED,
	/**
	 * A document chase was sent to the client (Unit 10, and Unit 19's timers later).
	 *
	 * <p>Its own action rather than an {@code UPDATED} row, because this is the column the
	 * board's "last chased" is read from — deriving it from the append-only trail is what
	 * lets the fact exist without a second place to keep it, and a query needs something to
	 * filter on. Nothing about the case itself changes when one is sent.
	 */
	CHASED
}
