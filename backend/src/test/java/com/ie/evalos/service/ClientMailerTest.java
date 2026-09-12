package com.ie.evalos.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the one mail channel EvalOS has will and will not do.
 *
 * <p>The interesting assertion is {@link #blankFromMeansNotConfigured()}: a missing sender must
 * degrade the sign-in flow, not throw from inside it.
 */
class ClientMailerTest {

	private final JavaMailSender sender = mock(JavaMailSender.class);

	@Test
	void setPasswordMailCarriesTheLink() {
		ClientMailer mailer = new ClientMailer(sender, "noreply@internationalevaluations.com");

		mailer.sendSetPassword("ana@example.com", "https://portal.example.com/set-password#tok");

		var captor = forClass(SimpleMailMessage.class);
		verify(sender).send(captor.capture());
		SimpleMailMessage sent = captor.getValue();
		assertThat(sent.getTo()).containsExactly("ana@example.com");
		assertThat(sent.getFrom()).isEqualTo("noreply@internationalevaluations.com");
		assertThat(sent.getText()).contains("https://portal.example.com/set-password#tok");
	}

	@Test
	void blankFromMeansNotConfigured() {
		ClientMailer mailer = new ClientMailer(sender, "");

		assertThat(mailer.isConfigured()).isFalse();

		assertThat(mailer.sendSetPassword("ana@example.com", "https://portal.example.com/set-password#tok"))
				.isFalse();

		verify(sender, never()).send(any(SimpleMailMessage.class));
	}

	/**
	 * A configured-but-failing sender reports false, exactly like an unconfigured one.
	 *
	 * <p><strong>The throw this replaces was an enumeration oracle.</strong> {@code MailException}
	 * is unchecked and propagated out of {@code ClientAccountService.forgotPassword}, so on an SMTP
	 * outage a <em>known</em> address answered 500 and an unknown one still answered 204 — the one
	 * difference that method exists to hide, appearing on precisely the day somebody is probing.
	 * It also turned {@code identify} into a 500 rather than {@code MAIL_UNAVAILABLE}, against
	 * this class's own promise never to make a mail problem a 500.
	 */
	@Test
	void aFailingSenderReportsFalseRatherThanThrowing() {
		ClientMailer mailer = new ClientMailer(sender, "noreply@internationalevaluations.com");
		org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp is down"))
				.when(sender).send(any(SimpleMailMessage.class));

		assertThat(mailer.sendSetPassword("ana@example.com", "https://x/#a")).isFalse();
		assertThat(mailer.sendResetPassword("ana@example.com", "https://x/#b")).isFalse();
	}

	@Test
	void resetMailIsADifferentMessageFromSetMail() {
		ClientMailer mailer = new ClientMailer(sender, "noreply@internationalevaluations.com");

		var captor = forClass(SimpleMailMessage.class);
		mailer.sendSetPassword("ana@example.com", "https://x/#a");
		mailer.sendResetPassword("ana@example.com", "https://x/#b");
		verify(sender, org.mockito.Mockito.times(2)).send(captor.capture());

		assertThat(captor.getAllValues().get(0).getSubject())
				.isNotEqualTo(captor.getAllValues().get(1).getSubject());
	}

	private static <T> T any(Class<T> type) {
		return org.mockito.ArgumentMatchers.any(type);
	}
}
