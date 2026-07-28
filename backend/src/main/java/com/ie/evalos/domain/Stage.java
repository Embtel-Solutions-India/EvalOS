package com.ie.evalos.domain;

/** The six internal pipeline stages EvalOS owns (stages 3-7 of the canonical model). */
public enum Stage {

	DOC_COLLECTION,
	EXPERT_ASSIGNMENT,
	DRAFT_GENERATION,
	EXPERT_SIGNING,
	FINAL_DELIVERY,
	CLOSED
}
