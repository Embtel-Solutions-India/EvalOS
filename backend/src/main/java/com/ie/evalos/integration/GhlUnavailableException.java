package com.ie.evalos.integration;

/**
 * A read from GHL's public API did not happen: no token in this environment, a refused
 * request, a call that did not answer inside its timeout, or a configured pipeline GHL
 * does not have.
 *
 * <p>Mapped to <strong>502</strong> by {@code ApiExceptionHandler}, alongside
 * {@link DriveUnavailableException} and for the same reason: the fault is upstream, and the
 * distinction from a 500 is what tells the reader to try again rather than to report a bug.
 *
 * <p><strong>Nothing in EvalOS changes when this is thrown.</strong> Every caller is a
 * read — the marketing pipeline view is a window onto GHL, not a copy of it — so there is
 * nothing to roll back and no retry queue is warranted.
 */
public class GhlUnavailableException extends RuntimeException {

	public GhlUnavailableException(String message) {
		super(message);
	}

	public GhlUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
