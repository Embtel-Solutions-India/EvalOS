package com.ie.evalos.common;

/**
 * 409 {@code DRAFT_NOT_CURRENT}: the caller acted on a draft version that is not the one in client
 * review (Unit 58 §6) — a stale tab approving v2 after v3 was sent, or a comment on history.
 */
public class DraftNotCurrentException extends RuntimeException {

	public DraftNotCurrentException(String message) {
		super(message);
	}
}
