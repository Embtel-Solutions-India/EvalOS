package com.ie.evalos.domain;

/**
 * The twelve production stages EvalOS owns (Unit 31).
 *
 * <p><strong>One stage, one owner, one primary action.</strong> This replaced a five-stage
 * pipeline that carried three further facts as sub-status columns — {@code pm_approval_status},
 * {@code client_approval_status}, {@code expert_sign_status} — which the board drew as chips.
 * That was right for a board and wrong for a workflow: a chip says what state work is in, it does
 * not say <em>whose turn it is</em>, and {@code DRAFT_GENERATION} was the Case Manager's stage
 * <em>and</em> the PM's review <em>and</em> the client's review, told apart only by two nullable
 * columns a reader had to combine correctly.
 *
 * <p><strong>Those three columns still exist and still matter.</strong> They record the
 * <em>outcome</em> of a review — approved, returned, revision-requested — which is a different
 * fact from where the case sits. What stopped being true is that the stage is derived from them.
 *
 * <p><strong>A stage is entered by the act that starts its clock.</strong> {@link #CLIENT_REVIEW}
 * begins when the Coordinator sends, not when the PM approves; {@link #EXPERT_SIGNING} begins when
 * the Case Manager sends, not when the client approves. That is why {@link #READY_TO_SEND} exists,
 * and it is why there is no {@code sent_to_client_at} or {@code sent_to_expert_at} column:
 * {@code stage_entered_at} <em>is</em> the send time. A timestamp beside a stage is a second answer
 * to "when did this begin", and two answers drift.
 *
 * <p><strong>The board draws eight columns, not twelve.</strong> Two stages share a column only
 * where they share an owner — see {@code STAGE_COLUMNS} in {@code boardRules.ts}.
 */
public enum Stage {

	/** 01 · Coordinator. Chase the client until every checklist item is in. */
	DOC_COLLECTION,

	/**
	 * 02 · Project Manager. Strategy notes, expert selection, CM assignment.
	 *
	 * <p>Renamed from {@code EXPERT_ASSIGNMENT}, which named one of the three things that happen
	 * here and hid the other two.
	 */
	PM_REVIEW,

	/** 03 · Case Manager. Write the draft, upload it, submit. Every revision loop lands here. */
	DRAFT_IN_PROGRESS,

	/** 04 · Project Manager. Approve for client review, or return with comments. */
	DRAFT_REVIEW,

	/**
	 * 05 · Coordinator. PM-approved and not yet with the client.
	 *
	 * <p><strong>This stage exists because {@link #CLIENT_REVIEW} is entered by the send.</strong>
	 * Somebody has to hold an approved draft between the approval and the sending, and that
	 * somebody is the Coordinator — so it is a stage rather than a gap. It mirrors
	 * {@link #READY_TO_DELIVER} exactly, which is the argument for it: the pipeline already had
	 * this shape at its end, and now both hand-offs are drawn the same way.
	 */
	READY_TO_SEND,

	/** 06 · Coordinator holds it; the client acts. Approve, or request revisions. */
	CLIENT_REVIEW,

	/**
	 * 07 · Case Manager. The client has approved and the approved version is locked.
	 *
	 * <p>Separate from {@link #CLIENT_REVIEW} on purpose: a review is a period, an approval is an
	 * event that fixes which version the expert signs and the business is paid for. The CM's
	 * primary action here is <em>Send to Expert for Signing</em>.
	 */
	CLIENT_APPROVAL,

	/** 08 · Case Manager holds it; the expert acts. Entered by the CM's send, which starts the SLA. */
	EXPERT_SIGNING,

	/** 09 · Project Manager. QC the signed document, or return it for correction. */
	FINAL_QC,

	/** 10 · Coordinator. QC-passed and not yet sent. */
	READY_TO_DELIVER,

	/** 11 · Coordinator. Sent to the client; the case is closed from here. */
	DELIVERED,

	/** 12 · Terminal. */
	CLOSED
}
