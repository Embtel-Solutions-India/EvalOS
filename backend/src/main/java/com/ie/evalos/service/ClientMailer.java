package com.ie.evalos.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.MailTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The only mail EvalOS sends (Unit 42).
 *
 * <p><strong>Two messages, and this is the boundary.</strong> Invariant 14 used to read "EvalOS
 * hosts no files and sends no email"; it now reads "…and sends email for exactly one purpose:
 * proving control of a client's own address". Set a password, reset a password. A status update,
 * a marketing message or a notification is a <em>different decision</em> and gets argued in its
 * own unit — and the first feature that asks for "just a quick email to the client" will present
 * itself as an obvious extension of this class. It is not.
 *
 * <p><strong>A blank sender degrades, it does not throw.</strong> An environment with no mail
 * configured still serves every screen. {@code ClientAccountService.issueCredential} asks
 * {@link #isConfigured()} first and, when the answer is no, mints nothing and answers
 * {@code MAIL_UNAVAILABLE} — which is the screen telling the client to contact us rather than to
 * wait for a link nobody sent. {@link #send} keeps its own guard anyway: this class must never be
 * the thing that turns a configuration gap into a 500, whatever a future caller forgets to ask.
 * A password reset is recoverable by a human; a refused boot is not.
 *
 * <p><strong>A failing sender degrades the same way, and that is the half review found
 * missing.</strong> The paragraph above was only ever true of a blank sender: the SMTP send threw
 * on an error or on any of the three five-second timeouts, and that propagated. On the
 * unauthenticated {@code forgot-password} route it propagated <em>selectively</em> — a known
 * address answered 500 while an unknown one still answered 204 — which turns the one method
 * written not to differentiate into an enumeration oracle on the day the mail host is down. So
 * every {@link MailTransport} reports <strong>whether the message actually left</strong> rather
 * than throwing, and a caller that cannot say something true is given the means to say nothing.
 *
 * <p><strong>This class owns the words; {@link MailTransport} owns the wire.</strong> The split
 * arrived when GHL took over sending and is kept because the next provider (Brevo) is expected —
 * adding one is a new {@code MailTransport} and a changed {@code evalos.mail.transport}, and this
 * class does not move. What a set-password mail <em>says</em> has nothing to do with who carries
 * it, and before the split those two facts lived in one method.
 */
@Service
public class ClientMailer {

	private static final Logger log = LoggerFactory.getLogger(ClientMailer.class);

	/**
	 * The one that sends. Chosen by {@code evalos.mail.transport} from every
	 * {@link MailTransport} on the classpath.
	 *
	 * <p><strong>Chosen by name rather than by {@code @Primary} or a profile.</strong> Switching
	 * providers is an environment change — the day GHL's sending domain is being re-verified, the
	 * fix is a variable and a restart, not a build. A name that matches nothing fails at startup
	 * and names what it found, because a typo that silently fell back to SMTP would be discovered
	 * by a client who never got their link.
	 */
	private final MailTransport transport;

	/**
	 * The trail for "a password link was sent to this person, then".
	 *
	 * <p><strong>Here rather than in a transport, which is where it started.</strong> It lived in
	 * {@code GhlMailTransport} because {@code GhlHttpTest} requires any class calling a GHL write
	 * verb to reach {@code AuditService} — a rule about GHL, which made the audit an accident of
	 * which provider happened to be carrying the mail. It is a fact about the client either way,
	 * and a support conversation needs it whoever sent it.
	 */
	private final AuditService audit;

	private final MailTemplates templates;

	ClientMailer(List<MailTransport> transports, AuditService audit, MailTemplates templates,
			@Value("${evalos.mail.transport}") String choice) {
		this.audit = audit;
		this.templates = templates;
		this.transport = transports.stream()
				.filter((candidate) -> candidate.name().equalsIgnoreCase(choice.trim()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("evalos.mail.transport is '" + choice
						+ "', which is not one of " + transports.stream().map(MailTransport::name).toList()));
		if (!this.transport.isConfigured()) {
			log.warn("Mail transport '{}' is not configured — client password mail is disabled. "
					+ "Sign-in still works for accounts that already have a password.",
					this.transport.name());
		}
	}

	/** Whether the chosen transport could send anything at all. */
	public boolean isConfigured() {
		return transport.isConfigured();
	}

	/** Whether this particular person is addressable — GHL needs a linked contact, SMTP does not. */
	public boolean canReach(MailTransport.Recipient to) {
		return transport.isConfigured() && transport.canReach(to);
	}

	/** @return whether the message left; see the class javadoc for why this is not a throw */
	public boolean sendSetPassword(MailTransport.Recipient to, String fullName, String link) {
		return send(to, templates.setPassword(fullName, link));
	}

	/** @return whether the message left; see the class javadoc for why this is not a throw */
	public boolean sendResetPassword(MailTransport.Recipient to, String fullName, String link) {
		return send(to, templates.resetPassword(fullName, link));
	}

	/**
	 * The confirmation a client gets when their request reaches Sales — 2026-09-19.
	 *
	 * <p><strong>This is the message that needed invariant 14 amended.</strong> The two above prove
	 * control of a mailbox, which is the one purpose that invariant allowed; this one does not. It
	 * was added on the business's instruction and the invariant was EDITED to say so rather than
	 * quietly widened — see {@code architecture.md}.
	 *
	 * <p>Unlike the two above, <strong>nothing depends on it arriving.</strong> The request is
	 * already submitted and already visible in the portal, so a failure here is logged and the
	 * submit still succeeds — which is why its caller ignores the boolean.
	 */
	public boolean sendRequestSubmitted(MailTransport.Recipient to, String fullName,
			String serviceName, int documentCount) {
		return send(to, templates.requestSubmitted(fullName, serviceName, documentCount));
	}

	private boolean send(MailTransport.Recipient to, MailTemplates.Message message) {
		String subject = message.subject();
		if (!canReach(to)) {
			// Not an exception: the caller has already decided what to tell the client, and a
			// throw here would turn a configuration gap into a 500 on a sign-in attempt.
			log.warn("Transport '{}' cannot reach {} — '{}' was not sent", transport.name(),
					to.email(), subject);
			return false;
		}
		if (!transport.send(to, subject, message.text(), message.html())) {
			return false;
		}
		// **The subject and the brand, and deliberately not the link.** The link IS the credential:
		// a trail that stored one would hand anyone who can read audit rows a working password
		// reset. recordPortalEvent rather than recordEvent, because a portal route has no
		// TenantContext and the row would otherwise land with no brand at all.
		//
		// **Caught, because the mail has already left.** recordPortalEvent is @Transactional and
		// neither caller of issueCredential is, so a transient database error here escaped as a 500
		// — for a known address, while an unknown one still answered 204. That difference is the
		// account-enumeration oracle this class's javadoc exists to close, arriving by way of the
		// trail rather than the mail. Worse, the 500 unwinds before the credential row is saved, so
		// the client holds a link that can never work.
		//
		// True, not false: the message left, and that is what this method's boolean means. A
		// missing trail row is a real problem and it is logged as one, but it is not the client's.
		try {
			audit.recordPortalEvent(to.brandId(), PortalAudience.CLIENT, "CLIENT_MAIL",
					UUID.nameUUIDFromBytes(("CLIENT_MAIL:" + to.email()).getBytes(StandardCharsets.UTF_8)),
					AuditAction.PORTAL_LINK_ISSUED, null,
					"sent '" + subject + "' via " + transport.name());
		}
		catch (RuntimeException trailFailed) {
			log.error("'{}' was sent via {} but the audit row could not be written", subject,
					transport.name(), trailFailed);
		}
		return true;
	}
}
