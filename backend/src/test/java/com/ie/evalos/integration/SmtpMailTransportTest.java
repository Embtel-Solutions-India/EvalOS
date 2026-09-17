package com.ie.evalos.integration;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The two things this transport decides on its own: whether it is configured, and what a failure
 * looks like. Everything else is Spring's.
 *
 * <p>The wire itself is {@link SmtpMailTransportLiveTest}'s, opt-in and against a real relay — a
 * mock cannot prove a credential.
 */
class SmtpMailTransportTest {

	private static final MailTransport.Recipient ANA =
			new MailTransport.Recipient(UUID.randomUUID(), "ana@example.com");

	/**
	 * <strong>A host and a sender, and this is the case that was wrong.</strong> Only
	 * {@code evalos.mail.from} was checked, so a deployment that set the sender and forgot the
	 * relay answered "configured" — and {@code ClientAccountService.issueCredential} then minted a
	 * token and told the client to watch their inbox for a mail no host could carry. Unconfigured
	 * has to be knowable before that promise is made.
	 */
	@Test
	void isConfiguredNeedsBothARelayAndASender() {
		JavaMailSender sender = mock(JavaMailSender.class);

		assertThat(new SmtpMailTransport(sender, "no-reply@example.com", "").isConfigured())
				.describedAs("a sender with no relay cannot send").isFalse();
		assertThat(new SmtpMailTransport(sender, "", "smtp.resend.com").isConfigured())
				.describedAs("a relay with no sender is refused by every provider").isFalse();
		assertThat(new SmtpMailTransport(sender, "  ", "  ").isConfigured())
				.describedAs("whitespace is not configuration").isFalse();
		assertThat(new SmtpMailTransport(sender, "no-reply@example.com", "smtp.resend.com").isConfigured())
				.isTrue();
	}

	/** Blank rows never become a send attempt, whatever the caller forgot to check. */
	@Test
	void aBlankAddressIsNotReachable() {
		SmtpMailTransport transport = new SmtpMailTransport(mock(JavaMailSender.class),
				"no-reply@example.com", "smtp.resend.com");

		assertThat(transport.canReach(new MailTransport.Recipient(UUID.randomUUID(), ""))).isFalse();
		assertThat(transport.canReach(new MailTransport.Recipient(UUID.randomUUID(), null))).isFalse();
		assertThat(transport.canReach(ANA)).isTrue();
	}

	/**
	 * <strong>A relay outage is false, never a throw</strong> — the contract the whole interface is
	 * written around. On the unauthenticated {@code forgot-password} route a propagating failure
	 * answers 500 for a known address beside 204 for an unknown one, which is an enumeration
	 * oracle appearing on the day the mail host is down.
	 */
	@Test
	void aRefusedSendIsFalseRatherThanAnException() {
		JavaMailSender sender = mock(JavaMailSender.class);
		doThrow(new MailSendException("relay refused")).when(sender).send(any(SimpleMailMessage.class));

		boolean left = new SmtpMailTransport(sender, "no-reply@example.com", "smtp.resend.com")
				.send(ANA, "Set your password", "link");

		assertThat(left).isFalse();
	}

	/** The name is what {@code evalos.mail.transport} matches, so it is pinned. */
	@Test
	void theNameIsSmtp() {
		JavaMailSender sender = mock(JavaMailSender.class);

		assertThat(new SmtpMailTransport(sender, "no-reply@example.com", "smtp.resend.com").name())
				.isEqualTo("smtp");
		verifyNoInteractions(sender);
	}
}
