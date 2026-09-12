package com.ie.evalos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
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
 * configured still serves every screen. {@code ClientAccountService.issueCredential} asks
 * {@link #isConfigured()} first and, when the answer is no, mints nothing and answers
 * {@code MAIL_UNAVAILABLE} — which is the screen telling the client to contact us rather than to
 * wait for a link nobody sent. {@link #send} keeps its own guard anyway: this class must never be
 * the thing that turns a configuration gap into a 500, whatever a future caller forgets to ask.
 * A password reset is recoverable by a human; a refused boot is not.
 *
 * <p><strong>A failing sender degrades the same way, and that is the half review found
 * missing.</strong> The paragraph above was only ever true of a blank {@code from}:
 * {@code JavaMailSender.send} throws the unchecked {@link MailException} on an SMTP error or on
 * any of the three five-second timeouts, and that propagated. On the unauthenticated
 * {@code forgot-password} route it propagated <em>selectively</em> — a known address answered 500
 * while an unknown one still answered 204 — which turns the one method written not to
 * differentiate into an enumeration oracle on the day the mail host is down. So both send methods
 * now report <strong>whether the message actually left</strong> rather than throwing, and a
 * caller that cannot say something true is given the means to say nothing.
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

	/** @return whether the message left; see the class javadoc for why this is not a throw */
	public boolean sendSetPassword(String toEmail, String link) {
		return send(toEmail, "Set your password",
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
	public boolean sendResetPassword(String toEmail, String link) {
		return send(toEmail, "Reset your password",
				"""
				Use the link below to choose a new password. It works once and expires in 30 \
				minutes.

				%s

				If you did not ask for this, you can ignore it — your current password still works.
				""".formatted(link));
	}

	private boolean send(String toEmail, String subject, String body) {
		if (!isConfigured()) {
			// Not an exception: the caller has already decided what to tell the client, and a
			// throw here would turn a configuration gap into a 500 on a sign-in attempt.
			log.warn("Mail not configured — '{}' to {} was not sent", subject, toEmail);
			return false;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		try {
			sender.send(message);
			return true;
		}
		catch (MailException e) {
			// **Logged with the address and swallowed.** An outage here must not reach the client
			// as a 500, and on `forgot-password` it must not reach them as a 500 for a known
			// address and a 204 for an unknown one. The operator needs the detail; the client
			// needs the two paths to stay indistinguishable. `false` is what carries the failure.
			log.error("Mail send failed — '{}' to {} was not delivered", subject, toEmail, e);
			return false;
		}
	}
}
