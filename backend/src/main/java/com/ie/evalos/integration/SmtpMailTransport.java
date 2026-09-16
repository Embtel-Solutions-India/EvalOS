package com.ie.evalos.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail, which is how EvalOS sent its two messages before GHL did.
 *
 * <p><strong>Kept rather than replaced.</strong> It is the transport with no third party in the
 * path: a laptop with MailHog, and the fallback for a deployment that has not given GHL the
 * conversations scope. It is also the only one whose failure mode is entirely local, which is
 * worth having when the question is "is our own mail broken or is theirs".
 *
 * <p>The body of this class is what {@code ClientMailer.send} was until the transport seam was
 * introduced; the wording moved up, the wire stayed here.
 */
@Component
public class SmtpMailTransport implements MailTransport {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

	private final JavaMailSender sender;

	private final String from;

	SmtpMailTransport(JavaMailSender sender, @Value("${evalos.mail.from:}") String from) {
		this.sender = sender;
		this.from = from == null ? "" : from.trim();
	}

	@Override
	public String name() {
		return "smtp";
	}

	@Override
	public boolean isConfigured() {
		return !from.isBlank();
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
