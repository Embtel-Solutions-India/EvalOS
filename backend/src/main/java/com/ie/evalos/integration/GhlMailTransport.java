package com.ie.evalos.integration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.service.AuditService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GHL sends the mail, so the client's password link lands in the same thread as everything else.
 *
 * <p><strong>Why route it through GHL at all.</strong> A set-password mail sent over SMTP exists
 * nowhere a salesperson can see. Sent this way it becomes a message on the contact's conversation,
 * on the sub-account's own sending domain and reputation, and available to GHL's own automation —
 * which is what {@code 00b} keeps GHL for.
 *
 * <p><strong>{@code contactId} is required, and that is the constraint this whole flow bends
 * around.</strong> Verified against {@code /docs/ghl/conversations/send-a-new-message}: there is no
 * contact-less send, and a {@code conversationId} is no escape because a conversation belongs to a
 * contact. It is the reason the GHL contact has to exist before the set-password mail rather than
 * after it, and therefore the reason D3a's <em>mechanism</em> changed.
 *
 * <p><strong>A person EvalOS has not linked yet is unreachable here, and says so.</strong>
 * {@link #canReach} is false for a null contact id rather than the send failing obscurely — a real
 * state, because a GHL outage at sign-up leaves an account with no contact and
 * {@code ensureCrmIdentity} repairs it on the next sign-in.
 *
 * <p><strong>Plain text, no {@code html}.</strong> The two messages are four lines and a link.
 * An HTML body would need a template, a template needs somebody to own it, and the one thing that
 * must never happen to a set-password mail is a rendering bug swallowing the link.
 */
@Component
public class GhlMailTransport implements MailTransport {

	private static final Logger log = LoggerFactory.getLogger(GhlMailTransport.class);

	/**
	 * What GHL answers. Bound loosely — only {@code messageId} is read, as proof something was
	 * accepted, and the rest of the envelope is free to grow without breaking this.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record SentMessage(String conversationId, String messageId, String emailMessageId) {
	}

	private final GhlHttp http;

	/**
	 * Invariant 13, and {@code GhlHttpTest.writeCallersAudit} fails the build without it: any class
	 * holding a {@code GhlHttp} and calling a write verb must reach here. It caught this class.
	 *
	 * <p>It is the right rule rather than a hoop. "A set-password link was sent to this contact at
	 * this time" is exactly what a support conversation needs and what an unaudited send makes
	 * invisible for ever — the same argument {@code ClientAccountService.signIn} makes about a
	 * refused sign-in.
	 */
	private final AuditService audit;

	/**
	 * The envelope sender, or blank to let GHL use the sub-account's default.
	 *
	 * <p>Blank is the better default and is not laziness: GHL knows which domain it has
	 * authenticated for this location, and an address set here that the location cannot sign for is
	 * how a password mail silently lands in spam.
	 */
	private final String from;

	GhlMailTransport(GhlHttp http, AuditService audit, @Value("${evalos.mail.ghl-from:}") String from) {
		this.http = http;
		this.audit = audit;
		this.from = from == null ? "" : from.trim();
	}

	/**
	 * A stable {@code audit_event.object_id} for a GHL contact — the same derivation
	 * {@code GhlWriteClient} uses, and namespaced the same way so one person's contact rows and
	 * their mail rows share a history rather than splitting into two.
	 */
	private static UUID auditKey(String ghlContactId) {
		return UUID.nameUUIDFromBytes(("GHL_CONTACT:" + ghlContactId).getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String name() {
		return "ghl";
	}

	@Override
	public boolean isConfigured() {
		return http.isConfigured();
	}

	@Override
	public boolean canReach(Recipient to) {
		return to.ghlContactId() != null && !to.ghlContactId().isBlank();
	}

	@Override
	public boolean send(Recipient to, String subject, String body) {
		if (!canReach(to)) {
			// Not an error and not a throw: the caller turns this into MAIL_UNAVAILABLE, which is a
			// screen telling the client something true rather than a 500.
			log.warn("No GHL contact for {} — '{}' was not sent", to.email(), subject);
			return false;
		}
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "Email");
		message.put("contactId", to.ghlContactId());
		message.put("subject", subject);
		message.put("message", body);
		message.put("status", "delivered");
		if (!from.isBlank()) {
			message.put("emailFrom", from);
		}
		// **emailTo is sent explicitly rather than left to the contact's primary address.** The
		// account's email is the one the client typed and the one the link is for; a contact whose
		// primary address has since been edited in GHL would otherwise receive a link to an account
		// it no longer names.
		message.put("emailTo", to.email());

		try {
			SentMessage sent = http.post(SentMessage.class,
					(uri) -> uri.path("/conversations/messages").build(), message);
			if (sent == null || sent.messageId() == null) {
				// GHL answered without accepting anything. Reported as a failure rather than
				// assumed a success: the cost of being wrong here is a client waiting for a mail
				// that never left.
				log.error("GHL accepted no message for '{}' to {}", subject, to.email());
				return false;
			}
			// **The contact id and the subject, and deliberately neither the address nor the
			// link.** The link IS the credential — a trail that stores one hands a database reader
			// a working password reset — and the address is contact PII that `GhlWriteClient` also
			// keeps out of this table. What a reader needs is "which person, which message, when",
			// and the contact id answers the first.
			// **recordPortalEvent, not recordEvent.** The latter derives the brand from
			// TenantContext, which a portal route does not have — every row would have landed with
			// a null brand_id and been invisible to every brand-scoped audit read. This overload
			// takes the brand explicitly for exactly that reason, which is why Recipient carries it.
			audit.recordPortalEvent(to.brandId(), PortalAudience.CLIENT, "GHL_CONTACT",
					auditKey(to.ghlContactId()), AuditAction.PORTAL_LINK_ISSUED, null,
					"sent '" + subject + "' to contact " + to.ghlContactId()
							+ " (GHL message " + sent.messageId() + ")");
			return true;
		}
		catch (RuntimeException refused) {
			log.error("GHL send failed — '{}' to {} was not delivered: {}", subject, to.email(),
					refused.getMessage());
			return false;
		}
	}
}
