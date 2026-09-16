package com.ie.evalos.integration;

/**
 * A read from GHL's public API did not happen: no token in this environment, a refused
 * request, a call that did not answer inside its timeout, or a configured pipeline GHL
 * does not have.
 *
 * <p>Mapped to <strong>502</strong> by {@code ApiExceptionHandler}, alongside
 * {@link DocumentStoreUnavailableException} and for the same reason: the fault is upstream, and the
 * distinction from a 500 is what tells the reader to try again rather than to report a bug.
 *
 * <p><strong>It now says WHY, not just that.</strong> {@link #failure()} classifies the fault at
 * the door (Unit 45, {@code 00d} §6.3) so a caller can tell a rate limit from a missing scope from
 * a timeout without parsing the message back out. Every one of them is still a 502 to a staff
 * caller — the fault is upstream either way — but the outbox, the delta sweep and the sync-status
 * surface all have to make a three-way decision that a string cannot carry.
 *
 * <p><strong>The old note here said "no retry queue is warranted", and that was true while every
 * caller was a read.</strong> It is not any more: EvalOS writes contacts and opportunities to GHL,
 * and Unit 45's outbox is exactly that queue. What is still true is that nothing in EvalOS is left
 * half-done when this is thrown from a read.
 */
public class GhlUnavailableException extends RuntimeException {

	private final GhlFailure failure;

	/**
	 * The status GHL returned, or null when nothing came back (timeout, transport failure, or a
	 * call that never left because the environment is unconfigured).
	 */
	private final Integer status;

	/**
	 * A fault EvalOS raised about GHL without a call having failed — an unconfigured environment, a
	 * configured name GHL does not have, a response missing a field.
	 *
	 * <p>Classified {@link GhlFailure#REFUSED}: <strong>not retriable</strong>, because sending the
	 * same request again changes nothing. Callers that know better say so through the four-argument
	 * constructor rather than letting a default decide.
	 */
	public GhlUnavailableException(String message) {
		this(message, null, GhlFailure.REFUSED, null);
	}

	public GhlUnavailableException(String message, Throwable cause) {
		this(message, cause, GhlFailure.REFUSED, null);
	}

	public GhlUnavailableException(String message, Throwable cause, GhlFailure failure, Integer status) {
		super(message, cause);
		this.failure = failure;
		this.status = status;
	}

	public GhlFailure failure() {
		return failure;
	}

	public Integer status() {
		return status;
	}

	/** Shorthand the outbox reads. See {@link GhlFailure#isRetriable()}. */
	public boolean isRetriable() {
		return failure.isRetriable();
	}
}
