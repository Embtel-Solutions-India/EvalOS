package com.ie.evalos.common;

/**
 * A request the caller could fix: a sheet whose column mapping names a field that does
 * not exist, a file in a format the import cannot read, a create naming no brand.
 *
 * <p>Its own type rather than {@code IllegalArgumentException} so that
 * {@link ApiExceptionHandler} can answer 400 with the message. A bare
 * {@code IllegalArgumentException} is thrown by library code and by
 * {@code AuditService} for a serialization failure, which is a 500 — one handler for
 * both would either hide a real fault or turn one into "your request was wrong".
 *
 * <p>Same rule as {@code IllegalTransitionException}: the message is returned to the
 * caller, so it may name a field, a format or a row — never whether an id outside the
 * caller's scope exists.
 */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}

	/**
	 * With the underlying fault kept as the cause.
	 *
	 * <p>For the case where a library exception is what <em>detected</em> the bad input — a
	 * {@code DateTimeParseException} on a malformed date, say. The message is still the
	 * caller-facing one; the cause is for the log, so a parse failure is diagnosable without
	 * putting a stack trace in the response.
	 */
	public InvalidRequestException(String message, Throwable cause) {
		super(message, cause);
	}
}
