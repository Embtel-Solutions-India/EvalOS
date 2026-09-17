package com.ie.evalos.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail — and since 2026-09-18, the only transport there is.
 *
 * <p><strong>It outlasted two provider-specific transports, and that is the argument for it.</strong>
 * A GHL transport addressed a {@code contactId}; a Brevo transport spoke {@code POST /v3/smtp/email}
 * with an {@code api-key} header. Each was a class, a config block, a credential and a live test
 * that proved exactly one vendor — and each was replaced within months. Every transactional
 * provider worth using also speaks SMTP on 587, so this one class reaches all of them and
 * <strong>changing provider is four environment variables and a restart, with no build</strong>.
 * The per-provider settings are tabulated over {@code spring.mail} in {@code application.yml}.
 *
 * <p><strong>What that trades away, stated plainly:</strong> an API transport can read a provider's
 * own response id, and Brevo's did. SMTP gives back a protocol-level accept and nothing more, so
 * "it left EvalOS" is the whole of what {@link #send} can promise — delivery, bounces and
 * suppression are read in the provider's dashboard or through its webhooks, neither of which is
 * built. For two messages whose failure is recoverable by a support conversation, that is the
 * cheaper side of the trade.
 *
 * <p>The body of this class is what {@code ClientMailer.send} was until the transport seam was
 * introduced; the wording moved up, the wire stayed here.
 */
@Component
public class SmtpMailTransport implements MailTransport {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

	private final JavaMailSender sender;

	private final String from;

	/**
	 * The relay, read only to answer {@link #isConfigured()}.
	 *
	 * <p><strong>Spring's own {@code JavaMailSender} will not tell us.</strong> It is auto-configured
	 * whether or not a host is set, so a deployment with {@code EVALOS_MAIL_FROM} and no
	 * {@code MAIL_HOST} used to report itself configured and then fail one send at a time — which
	 * is the state the interface's two-method split exists to keep apart. Unconfigured must be
	 * known before a client is told a link is coming.
	 */
	private final String host;

	SmtpMailTransport(JavaMailSender sender, @Value("${evalos.mail.from:}") String from,
			@Value("${spring.mail.host:}") String host) {
		this.sender = sender;
		this.from = from == null ? "" : from.trim();
		this.host = host == null ? "" : host.trim();
	}

	@Override
	public String name() {
		return "smtp";
	}

	/** Both, because either alone cannot send: a sender with no relay never leaves, and a relay
	 * with no sender is refused by every provider. */
	@Override
	public boolean isConfigured() {
		return !from.isBlank() && !host.isBlank();
	}

	@Override
	public boolean send(Recipient to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to.email());
		message.setSubject(subject);
		message.setText(body);
		try {
			sender.send(message);
			return true;
		}
		catch (MailException failed) {
			// **Logged with the address and swallowed.** An outage here must not reach the client
			// as a 500, and on `forgot-password` it must not reach them as a 500 for a known
			// address and a 204 for an unknown one. The operator needs the detail; the client
			// needs the two paths to stay indistinguishable.
			log.error("SMTP send failed — '{}' to {} was not delivered", subject, to.email(), failed);
			return false;
		}
	}
}
