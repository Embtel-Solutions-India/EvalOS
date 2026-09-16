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

	ClientMailer(List<MailTransport> transports, AuditService audit,
			@Value("${evalos.mail.transport}") String choice) {
		this.audit = audit;
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
	public boolean sendSetPassword(MailTransport.Recipient to, String link) {
		return send(to, "Set your password",
				"""
				Welcome.

				Use the link below to set a password for your account. It works once and expires \
				in 30 minutes.

				%s

				If you did not expect this, you can ignore it — nothing changes until the link is \
				used.
				""".formatted(link));
	}

	/** @return whether the message left; see the class javadoc for why this is not a throw */
	public boolean sendResetPassword(MailTransport.Recipient to, String link) {
		return send(to, "Reset your password",
				"""
				Use the link below to choose a new password. It works once and expires in 30 \
				minutes.

				%s

				If you did not ask for this, you can ignore it — your current password still works.
				""".formatted(link));
	}

	private boolean send(MailTransport.Recipient to, String subject, String body) {
		if (!canReach(to)) {
			// Not an exception: the caller has already decided what to tell the client, and a
			// throw here would turn a configuration gap into a 500 on a sign-in attempt.
			log.warn("Transport '{}' cannot reach {} — '{}' was not sent", transport.name(),
					to.email(), subject);
			return false;
		}
		if (!transport.send(to, subject, body)) {
			return false;
		}
		// **The subject and the brand, and deliberately not the link.** The link IS the credential:
		// a trail that stored one would hand anyone who can read audit rows a working password
		// reset. recordPortalEvent rather than recordEvent, because a portal route has no
		// TenantContext and the row would otherwise land with no brand at all.
		audit.recordPortalEvent(to.brandId(), PortalAudience.CLIENT, "CLIENT_MAIL",
				UUID.nameUUIDFromBytes(("CLIENT_MAIL:" + to.email()).getBytes(StandardCharsets.UTF_8)),
				AuditAction.PORTAL_LINK_ISSUED, null, "sent '" + subject + "' via " + transport.name());
		return true;
	}
}
