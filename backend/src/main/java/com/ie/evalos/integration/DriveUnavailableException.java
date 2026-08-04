package com.ie.evalos.integration;

/**
 * The Drive upload did not happen: no credentials in this environment, a refused
 * request, or a call that did not answer inside its timeout.
 *
 * <p>Mapped to <strong>502</strong> by {@code ApiExceptionHandler}, not 500: the fault is
 * an upstream one, and the distinction is what tells the PM to try again rather than to
 * report a bug. <strong>Nothing in EvalOS changes when this is thrown</strong> — the
 * profile is generated on demand from the roster row, so there is nothing to roll back
 * and no retry queue is warranted (Unit 13 spec). The audit row is written only after a
 * successful upload, so a failed write leaves no trail claiming a document exists.
 */
public class DriveUnavailableException extends RuntimeException {

	public DriveUnavailableException(String message) {
		super(message);
	}

	public DriveUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
