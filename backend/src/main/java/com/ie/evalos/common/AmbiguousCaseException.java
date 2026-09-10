package com.ie.evalos.common;

/**
 * A party-scoped portal token was presented to a route that acts on <em>one</em> case, and the
 * party has more than one (Unit 35, D1).
 *
 * <p><strong>Why this refuses instead of picking.</strong> The single-case routes include
 * {@code approve} and {@code request-revisions}. Choosing the newest case for a client who has two
 * would let them approve a draft they were not looking at, and the approval is a transition that
 * moves the case toward delivery — there is no undo that reaches the client. Refusing costs one
 * round trip; guessing costs the wrong letter going out.
 *
 * <p><strong>Its own code, not {@code ILLEGAL_TRANSITION}.</strong> A 409 already means "the case
 * cannot do that right now", and a portal that cannot tell the two apart shows a client "that is
 * not allowed" when the honest answer is "say which one". This is the one refusal on the portal
 * surface that is a request for more information rather than a denial, so it gets a code the SPA
 * can branch on.
 *
 * <p>It is not an existence oracle: the holder of a party token already knows how many cases they
 * have, because the list route tells them.
 */
public class AmbiguousCaseException extends RuntimeException {

	public AmbiguousCaseException(String message) {
		super(message);
	}
}
