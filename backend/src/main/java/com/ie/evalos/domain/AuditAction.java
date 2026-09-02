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
	 * A generated document left EvalOS. <strong>Retired with Unit 13 (2026-09-02) and kept
	 * anyway.</strong>
	 *
	 * <p>Its only writer was the redacted expert profile, and that unit is removed — so nothing
	 * writes this now. <strong>Do not delete the value.</strong> The audit trail is append-only by
	 * invariant and its rows can never be rewritten, so an enum that cannot read a value some
	 * historical row carries would fail on read. A retired audit action stays readable forever;
	 * that is the cost of an immutable history and it is a cost worth paying.
	 *
	 * <p>The frontend's {@code AuditAction} union and {@code Timeline}'s label map keep it for the
	 * same reason.
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
	PORTAL_LINK_ISSUED,
	/**
	 * A Case Manager raised a blocked case to the Project Managers on its brand (Unit 22, slice 3).
	 *
	 * <p>Its own action rather than an {@code UPDATED} row, for the reason {@link #CHASED} is:
	 * nothing about the case changed. What happened is that somebody asked for help, and "when was
	 * this escalated, and what did they say" is a question the timeline has to be able to answer —
	 * the reason travels in the snapshot's note.
	 */
	FLAGGED,
	/**
	 * An Expert Network Manager recorded a performance concern against an expert (Unit 22,
	 * slice 4), written against the <strong>expert</strong> rather than a case.
	 *
	 * <p>This is a human judgement, which is exactly why it is audited: {@code performance_flags}
	 * is a column somebody sets about somebody else, and the trail is what makes it answerable —
	 * who flagged this expert, when, and why. Counts of declines are <em>not</em> recorded this
	 * way; those are read from {@code expert_case_offer}, where they are events rather than
	 * opinions.
	 */
	PERFORMANCE_FLAGGED,
	/**
	 * The client asked for changes to a drafted letter (Unit 22, slice 3).
	 *
	 * <p>Its own action because it is the one the <strong>Case Manager's client-revision rate</strong>
	 * is counted from, and a metric needs something to filter on. It used to share
	 * {@link #UPDATED} with strategy-note edits, deadline changes, draft submissions and half the
	 * draft loop — so counting "how often did a client ask for changes" was not possible without
	 * parsing the stored snapshot.
	 *
	 * <p><strong>Rows written before this existed are {@code UPDATED} and stay that way.</strong>
	 * The trail is append-only, so the rate is forward-looking rather than retrospective. That is
	 * acceptable here and would not be after launch — the alternative was a migration that rewrites
	 * history, which the append-only rule forbids outright.
	 */
	CLIENT_REVISION_REQUESTED,
	/**
	 * Somebody working the case wrote a note on it (Unit 23).
	 *
	 * <p>Its own action for the reason {@link #CHASED} and {@link #FLAGGED} are: nothing about
	 * the case changed. What happened is that a person said something the next person needs,
	 * and the note travels in the snapshot's {@code note} exactly like a hold reason does.
	 *
	 * <p><strong>This is why there is no {@code case_note} table.</strong> The trail is already
	 * append-only three times over, already brand-scoped, already resolves actor names and
	 * already interleaves with the transitions a note is usually about — a second store beside
	 * it would have to re-earn all four and be merged on read. The cost of the choice is stated
	 * plainly: a note cannot be edited or withdrawn, ever, by anyone. That is invariant 13
	 * working as intended, not a limitation to design around.
	 *
	 * <p>Who may write one is <em>not</em> a role list. {@code POST /cases/{id}/notes} carries no
	 * {@code @PreAuthorize}; the scoped load is the whole gate, so "everyone on the case" means
	 * precisely the set the scope already admits.
	 */
	NOTE_ADDED,
	/**
	 * A transfer was recorded as sent, settling one or more payout rows (Unit 16b),
	 * written against the <strong>payment</strong>.
	 *
	 * <p>Its own action rather than {@code CREATED}, for the reason {@link #CHASED} and
	 * {@link #FLAGGED} have theirs: this is the row every "money out" question filters
	 * on, and a metric needs something to filter on. Confirming a payment and correcting
	 * a reference stay {@code UPDATED} — those are ordinary field changes on a record
	 * that already exists.
	 *
	 * <p>Never reaches the case timeline: the object acted on is a payment, not a case.
	 */
	PAYOUT_SETTLED
}
