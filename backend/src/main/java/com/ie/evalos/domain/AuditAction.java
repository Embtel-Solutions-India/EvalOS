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
	CHASED,
	/**
	 * A bulk sheet import ran (Unit 11).
	 *
	 * <p>Recorded against the <em>brand</em>, because the object acted on is the brand's
	 * roster and no single expert row is the subject. The per-expert {@code CREATED} and
	 * {@code UPDATED} rows are written as well, so the trail answers both "what happened
	 * to this expert" and "where did fifty experts come from at once".
	 */
	IMPORTED,
	/**
	 * A generated document left EvalOS (Unit 13: the redacted expert profile written into
	 * the case's Google Drive folder).
	 *
	 * <p>Its own action rather than an {@code UPDATED} row, because nothing about the case
	 * changed — a document was published toward the client, which is the fact worth a
	 * permanent record. The snapshot carries the Drive file and folder ids, so the trail
	 * answers "which document, and where did it go" and not merely "something was exported".
	 *
	 * <p>The frontend's {@code AuditAction} union and {@code Timeline}'s label map already
	 * carried this value before anything wrote it.
	 */
	EXPORTED,
	/**
	 * A portal link was minted for a case (Unit 14: the client's draft-review link; Unit 15's
	 * expert link goes through the same service).
	 *
	 * <p>Its own action rather than {@code EXPORTED}: no document left EvalOS, a
	 * <em>credential</em> was issued toward somebody outside the company, and re-minting one
	 * revokes the last. That is worth a permanent record of its own — including on the staff
	 * timeline, where "who sent the client a link, and when" is the question support asks.
	 *
	 * <p>The snapshot records the audience and the expiry and <strong>never the token</strong>,
	 * which exists exactly once, in the response to the mint.
	 */
	PORTAL_LINK_ISSUED
}
