package com.ie.evalos.domain;

/**
 * Where a {@link CaseDocument} version stands. Mirrors {@code case_document_status_known} (V31).
 *
 * <p>This is the version's own state and is <strong>not</strong> the case's stage. A case in
 * {@code DRAFT_REVIEW} has a {@code SUBMITTED} version in front of the PM; the same case a minute
 * later is in {@code DRAFT_IN_PROGRESS} with that version {@code RETURNED}. Reading one off the
 * other is the two-part inference Unit 31 removed from the stage, and it must not come back here.
 */
public enum DocumentStatus {

	/** Uploaded and waiting on a reviewer. */
	SUBMITTED,
	/** The PM sent it back. The comment says why. */
	RETURNED,
	/** The PM passed it for the client to read. */
	PM_APPROVED,
	/** The client accepted it. This is the version the expert signs; it is locked. */
	CLIENT_APPROVED,
	/** The expert signed it. */
	SIGNED,
	/** A newer version replaced it before anybody ruled on it. */
	SUPERSEDED
}
