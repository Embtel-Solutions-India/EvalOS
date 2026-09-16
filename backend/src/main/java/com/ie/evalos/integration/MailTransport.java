package com.ie.evalos.integration;

/**
 * How a message actually leaves the building.
 *
 * <p><strong>An interface with more than one implementation, which is the only reason it exists.</strong>
 * EvalOS sends two messages in its whole life — set-password and reset-password — and a seam under
 * that would normally be ceremony. It is here because the answer to "who sends them" has already
 * changed twice (SMTP → GHL → Brevo), and each change was
 * otherwise an edit to the one class that knows what the messages <em>say</em>. Splitting the
 * wording from the wire is what makes the third switch a new file rather than a rewrite.
 *
 * <p><strong>The recipient is an address and a brand, and it used to carry a GHL contact id
 * too.</strong> That field existed for one transport: GHL's send required a {@code contactId} and
 * would not accept a bare address. Every remaining transport addresses an email, so it has no
 * reader and is gone — along with the state it created, where a configured transport could still
 * be unable to reach a particular client.
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
	 * @param brandId whose client this is. Carried explicitly because a portal route has no
	 *                {@code TenantContext} to derive it from — an audit row written for one of
	 *                these without a brand lands unbranded and is invisible to every brand-scoped
	 *                read, which is the rule CLAUDE.md states first. Review caught that.
	 * @param email   the address. Always present — it is the account's login.
	 */
	record Recipient(java.util.UUID brandId, String email) {
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
	 * <p><strong>Every transport now answers this the same way</strong>, and the method survives
	 * the one that did not because it is still a real guard — a row with a blank email must not
	 * become a send attempt. While GHL carried the mail this was load-bearing: it could be
	 * configured and still unable to reach a client with no linked contact, a distinction no
	 * address-based provider has.
	 */
	default boolean canReach(Recipient to) {
		return to.email() != null && !to.email().isBlank();
	}

	/**
	 * @return whether the message left. Never throws — see the type javadoc.
	 */
	boolean send(Recipient to, String subject, String body);
}
