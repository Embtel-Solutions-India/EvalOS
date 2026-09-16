package com.ie.evalos.integration;

/**
 * How a message actually leaves the building.
 *
 * <p><strong>An interface with more than one implementation, which is the only reason it exists.</strong>
 * EvalOS sends two messages in its whole life — set-password and reset-password — and a seam under
 * that would normally be ceremony. It is here because the answer to "who sends them" has already
 * changed once (SMTP → GHL) and is expected to change again (→ Brevo), and each change was
 * otherwise an edit to the one class that knows what the messages <em>say</em>. Splitting the
 * wording from the wire is what makes the third switch a new file rather than a rewrite.
 *
 * <p><strong>The recipient carries both keys, and that is deliberate rather than sloppy.</strong>
 * GHL addresses a person by {@code contactId} and will not accept a bare address —
 * {@code POST /conversations/messages} requires it, verified against the docs. SMTP and Brevo
 * address them by email and have no idea what a contact id is. A recipient carrying only the
 * intersection would make GHL unimplementable; one carrying a union of two optional fields lets
 * each transport take what it needs and lets {@link #canReach} answer honestly for both.
 *
 * <p><strong>Nothing here throws.</strong> A mail outage must not become a 500 on a sign-in
 * attempt, and on {@code forgot-password} it must not become a 500 for a known address beside a
 * 204 for an unknown one — which is an enumeration oracle appearing on exactly the day somebody is
 * probing. Every method reports a boolean and logs the detail for whoever is on call.
 */
public interface MailTransport {

	/**
	 * Who to send to, by whichever name the transport understands.
	 *
	 * @param email        the address. Always present — it is the account's login.
	 * @param ghlContactId GHL's own id for this person, or null if EvalOS has not linked one yet.
	 *                     Only {@code GhlMailTransport} reads it.
	 */
	record Recipient(String email, String ghlContactId) {
	}

	/** A name for logs and for {@code evalos.mail.transport}. Lowercase, one word. */
	String name();

	/**
	 * Whether this transport could send anything at all — credentials present, sender configured.
	 *
	 * <p>Separate from {@link #canReach} because the two failures need different words in front of
	 * a client: nothing configured is "contact us", while a configured transport that cannot reach
	 * <em>this</em> person is a gap EvalOS can close on its own.
	 */
	boolean isConfigured();

	/**
	 * Whether this particular person is addressable by this transport.
	 *
	 * <p>Always true for an address-based transport. False for GHL when the contact has not been
	 * linked yet — which is a real state, because a GHL outage at sign-up leaves an account with
	 * no contact id and {@code ensureCrmIdentity} repairs it later.
	 */
	default boolean canReach(Recipient to) {
		return to.email() != null && !to.email().isBlank();
	}

	/**
	 * @return whether the message left. Never throws — see the type javadoc.
	 */
	boolean send(Recipient to, String subject, String body);
}
