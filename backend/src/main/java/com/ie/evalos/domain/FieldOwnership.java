package com.ie.evalos.domain;

import java.util.Set;

/**
 * Who owns a mirrored field when the two systems disagree — Unit 45, slice E.
 *
 * <p><strong>This exists to replace "EvalOS wins", which was the wrong rule.</strong> {@code 00c}
 * §4b proposed that blanket policy and {@code 45-sync-engine.md} §3.2 rejects it in one sentence:
 * it <em>reverts GHL automations</em>, which is the one thing {@code 00b} explicitly kept GHL for.
 * A round-robin reassigning a deal is not a conflict to be undone — it is GHL doing its job.
 *
 * <p><strong>Row-level timestamps are the other rejected rule.</strong> Comparing one
 * {@code updated_at} against another makes <em>every</em> concurrent edit a conflict, including two
 * edits to different fields that could both have stood. Ownership is per field, in code, and that
 * is what dissolves most of them.
 *
 * <p>Three kinds, and every mirrored field is exactly one:
 *
 * <ul>
 * <li>{@link #GHL} — GHL writes it and EvalOS never argues. The assignee, the pipeline, GHL's own
 * timestamps. A disagreement here is not a conflict, it is EvalOS being behind, and the mirror
 * simply takes GHL's value.</li>
 * <li>{@link #SHARED} — both sides write it, so a genuine conflict is possible. <strong>EvalOS
 * wins and it is reported</strong> (§3.2): the mirror keeps the local value and the nightly audit
 * opens a drift row, so a human sees the disagreement rather than a silent overwrite losing an
 * edit that has not reached GHL yet.</li>
 * <li>{@link #EVALOS} — EvalOS's alone and never synced in either direction. Staff prose about a
 * deal is not CRM data, and sending it would be the invariant-14 question all over again.</li>
 * </ul>
 *
 * <p><strong>The field names are the entity's property names</strong>, not GHL's JSON keys, because
 * that is what {@code sync_drift.field} already records and a second vocabulary would need a
 * mapping nobody would maintain.
 */
public enum FieldOwnership {

	/** GHL's. The mirror takes GHL's value every time; a difference is staleness, not conflict. */
	GHL,

	/** Both write it. EvalOS wins a genuine conflict, and the conflict is reported. */
	SHARED,

	/** EvalOS's alone, never synced either way. */
	EVALOS;

	/** The stage the deal sits in. A desk moves cards; so does a GHL workflow. */
	public static final String STAGE = "ghlStageId";

	/** open / won / lost / abandoned. Same two writers. */
	public static final String STATUS = "status";

	/** The deal's value. */
	public static final String AMOUNT = "amount";

	/** The deal's name. */
	public static final String NAME = "name";

	/** The GHL user the deal is assigned to — <strong>GHL's, and this is the load-bearing one.</strong> */
	public static final String ASSIGNED_TO = "ghlAssignedTo";

	/** Which pipeline the deal is on. GHL's: placement is its automation's job (D11). */
	public static final String PIPELINE = "pipelineId";

	private static final Set<String> SHARED_FIELDS = Set.of(STAGE, STATUS, AMOUNT, NAME);

	private static final Set<String> EVALOS_FIELDS = Set.of("opportunityNote");

	/**
	 * Who owns this field.
	 *
	 * <p><strong>An unknown field answers {@link #GHL}, deliberately.</strong> Everything in the
	 * mirror is GHL's by default — a new column arrives because GHL started returning something —
	 * so the safe default is to follow GHL rather than to start defending a value EvalOS has never
	 * written. A field EvalOS does write is a field somebody had to add to {@code SHARED_FIELDS},
	 * which is the edit a reviewer can see.
	 */
	public static FieldOwnership of(String field) {
		if (field == null) {
			return GHL;
		}
		if (SHARED_FIELDS.contains(field)) {
			return SHARED;
		}
		return EVALOS_FIELDS.contains(field) ? EVALOS : GHL;
	}
}
