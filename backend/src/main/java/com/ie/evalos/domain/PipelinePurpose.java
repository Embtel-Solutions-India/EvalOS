package com.ie.evalos.domain;

/**
 * What EvalOS uses a mirrored GHL pipeline for — the one column on {@code pipeline} GHL does not
 * own.
 *
 * <p><strong>It replaces a property per pipeline.</strong> Config carried three
 * {@code *-pipeline-name} settings and {@code application.yml} explicitly forbade a list ("THREE
 * NAMES, NOT A LIST") — right at three pipelines, wrong at the nine the business is moving to
 * ({@code 00d} §6.7). The mirrored table is the list; this says which row means what.
 *
 * <p><strong>Nothing infers a purpose from a pipeline's name.</strong> A GM sets it. Guessing from
 * text is how a rename in GHL silently reroutes a client's request, which is the same failure that
 * made {@code GhlPipelineClient.pipelineNamed} a liability worth retiring.
 */
public enum PipelinePurpose {

	/** A campaign funnel — leads a channel produced. */
	MARKETING,

	/** A salesperson's working pipeline. */
	SALES,

	/**
	 * The Case Delivery pipeline, mirrored for reporting and hand-off visibility only.
	 *
	 * <p><strong>It must never become a second case lifecycle.</strong> A GHL pipeline whose stages
	 * track delivery is structurally a second case board, and invariant 8 plus {@code 43} §9 forbid
	 * exactly that shape — {@code evalos_case} stays the only lifecycle. {@code 00d} §6.7 names
	 * this as one of three things mirroring it must not quietly do.
	 */
	DELIVERY,

	/** Where a client's own request lands when the portal opens it. */
	INTAKE,

	/**
	 * GHL has this pipeline and EvalOS has not been told what it is for.
	 *
	 * <p>The default, and the safe one: a pipeline that appears in GHL tomorrow arrives meaning
	 * nothing here rather than being guessed into a routing decision.
	 */
	UNASSIGNED
}
