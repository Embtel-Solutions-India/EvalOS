package com.ie.evalos.domain;

/** State of one required document on a case. */
public enum ChecklistItemStatus {

	REQUIRED,
	UPLOADED,
	APPROVED,
	MISSING,
	INCORRECT;

	/**
	 * The one definition of "this document is in".
	 *
	 * <p>Lives on the enum because three places have to agree on it and disagreement is
	 * invisible: {@code markDocsComplete} gates the transition on it, the case detail's
	 * summary chip counts with it, and Unit 10's board draws the completeness bar from it.
	 * A chip reading "6 of 6" over a transition that then refuses is worse than no chip,
	 * and that can only be guaranteed by there being one predicate rather than three
	 * kept in step by comment.
	 */
	public boolean isComplete() {
		return this == UPLOADED || this == APPROVED;
	}
}
