package com.ie.evalos.integration;

/**
 * The document store did not answer. A 502, never a 500.
 *
 * <p>The same shape and the same reasoning as {@link GhlUnavailableException}: the fault is
 * upstream, nothing in EvalOS changed, and the caller should retry rather than report a bug. It
 * replaces {@code DriveUnavailableException}, which said the same thing about a different store.
 */
public class DocumentStoreUnavailableException extends RuntimeException {

	public DocumentStoreUnavailableException(String message) {
		super(message);
	}

	public DocumentStoreUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
