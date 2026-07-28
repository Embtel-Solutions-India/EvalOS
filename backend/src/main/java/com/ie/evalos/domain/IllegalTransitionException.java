package com.ie.evalos.domain;

/**
 * The action is not declared for the state the case is in. Mapped to 409 by
 * {@code ApiExceptionHandler}: the request was well formed and the caller was
 * permitted — the case simply is not where the action expects it.
 *
 * <p>The stage guards (a PM must be assigned, every checklist item complete, the
 * draft PM-approved before it goes to the client) raise this too. They are the
 * same kind of failure as an undeclared {@code (from, action)} pair: a conflict
 * with current state, not a bad request.
 */
public class IllegalTransitionException extends RuntimeException {

	public IllegalTransitionException(String message) {
		super(message);
	}
}
