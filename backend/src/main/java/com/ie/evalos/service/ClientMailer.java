package com.ie.evalos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
 * configured still serves every screen; {@code ClientAccountService} asks {@link #isConfigured()}
 * and tells the client to contact support instead. A password reset is recoverable by a human; a
 * refused boot is not.
 */
@Service
public class ClientMailer {

	private static final Logger log = LoggerFactory.getLogger(ClientMailer.class);

	private final JavaMailSender sender;

	private final String from;

	ClientMailer(JavaMailSender sender, @Value("${evalos.mail.from:}") String from) {
		this.sender = sender;
		this.from = from == null ? "" : from.trim();
		if (this.from.isBlank()) {
			log.warn("evalos.mail.from is not set — client password mail is disabled. "
					+ "Sign-in still works for accounts that already have a password.");
		}
	}

	public boolean isConfigured() {
		return !from.isBlank();
	}

	public void sendSetPassword(String toEmail, String link) {
		send(toEmail, "Set your password",
				"""
				Welcome.

				Use the link below to set a password for your account. It works once and expires \
				in 30 minutes.

				%s

				If you did not expect this, you can ignore it — nothing changes until the link is \
				used.
				""".formatted(link));
	}

	public void sendResetPassword(String toEmail, String link) {
		send(toEmail, "Reset your password",
				"""
				Use the link below to choose a new password. It works once and expires in 30 \
				minutes.

				%s

				If you did not ask for this, you can ignore it — your current password still works.
				""".formatted(link));
	}

	private void send(String toEmail, String subject, String body) {
		if (!isConfigured()) {
			// Not an exception: the caller has already decided what to tell the client, and a
			// throw here would turn a configuration gap into a 500 on a sign-in attempt.
			log.warn("Mail not configured — '{}' to {} was not sent", subject, toEmail);
			return;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		sender.send(message);
	}
}
