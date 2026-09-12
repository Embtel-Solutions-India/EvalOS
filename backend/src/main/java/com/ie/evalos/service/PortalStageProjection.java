package com.ie.evalos.service;

import com.ie.evalos.domain.Stage;

/**
 * Unit 31's twelve stages, projected into the one word a portal caller should see (Unit 35, D5).
 *
 * <p><strong>This is the only place the mapping exists.</strong> The portals held four overlapping
 * vocabularies before this — {@code RequestStatus}, {@code SigningStatus}, {@code DocumentStatus},
 * {@code IntakeDocumentStatus} — none of which agreed with {@code CaseTransitions}, which is the
 * one thing that actually decides what a case may do next. EvalOS serves the step; the SPA renders
 * the string it was given and holds no lifecycle enum of its own. A convenience constant on the
 * client is how that erodes, so it is worth failing a review over.
 *
 * <p><strong>{@code actionRequired} is a field, not a suffix.</strong> The target design writes the
 * two urgent steps as "Upload — action required" and "Review — action required", and it would be
 * easy to ship exactly that string and let the SPA look for the dash. Then the flag that decides
 * whether a row is highlighted lives in a substring search, and it breaks the first time somebody
 * rewords the label. The label is for reading; the boolean is for branching.
 *
 * <p><strong>An expert has no step before signing.</strong> Stages 1–7 answer {@code null}, and
 * the payload carries null through rather than inventing a placeholder — an expert on a case that
 * has not reached them is shown nothing about it, which is also the answer that leaks least about
 * a case they are on the panel for but not yet working.
 */
public final class PortalStageProjection {

	/**
	 * One step, as a portal caller sees it.
	 *
	 * @param label          what to render, EvalOS's words
	 * @param actionRequired whether this step is waiting on <em>this</em> caller
	 */
	public record PortalStep(String label, boolean actionRequired) {
	}

	private static final PortalStep IN_PROGRESS = new PortalStep("In progress", false);

	private static final PortalStep SUBMITTED = new PortalStep("Submitted", false);

	private PortalStageProjection() {
	}

	/**
	 * What the client sees. Every stage answers something — a client always has a case to look at.
	 *
	 * <p>Two of the twelve are the client's to act on: {@code DOC_COLLECTION}, where nothing moves
	 * until they upload, and {@code CLIENT_REVIEW}, where the draft is waiting on their approval.
	 * {@code CLIENT_APPROVAL} is deliberately <em>not</em> one of them — by then they have
	 * approved and the case is EvalOS's again.
	 */
	public static PortalStep forClient(Stage stage) {
		return switch (stage) {
			case DOC_COLLECTION -> new PortalStep("Upload", true);
			case CLIENT_REVIEW -> new PortalStep("Review", true);
			case DELIVERED -> new PortalStep("Ready to download", false);
			case CLOSED -> new PortalStep("Complete", false);
			case PM_REVIEW, DRAFT_IN_PROGRESS, DRAFT_REVIEW, READY_TO_SEND, CLIENT_APPROVAL, EXPERT_SIGNING,
					FINAL_QC, READY_TO_DELIVER -> IN_PROGRESS;
		};
	}

	/**
	 * What the expert sees, or {@code null} before the case reaches them.
	 *
	 * <p>One stage is theirs to act on, and it is the one the 20h/24h prompts chase.
	 */
	public static PortalStep forExpert(Stage stage) {
		return switch (stage) {
			case EXPERT_SIGNING -> new PortalStep("Sign", true);
			case FINAL_QC, READY_TO_DELIVER, DELIVERED, CLOSED -> SUBMITTED;
			case DOC_COLLECTION, PM_REVIEW, DRAFT_IN_PROGRESS, DRAFT_REVIEW, READY_TO_SEND, CLIENT_REVIEW,
					CLIENT_APPROVAL -> null;
		};
	}
}
