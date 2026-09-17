package com.ie.evalos.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that actually sends a mail, and the only thing that can close a provider's setup.
 *
 * <p><strong>Why nothing cheaper does.</strong> {@link SmtpMailTransportTest} pins what this class
 * decides and {@code ClientMailerTest} pins the seam, both with mocks and fakes — neither touches
 * the wire. What is at risk is everything the wire decides: that the host and port are the
 * provider's, that the username is what that provider means by one (Resend wants the literal
 * {@code resend}; Brevo wants its own SMTP login, which is not the account email), that the
 * password is a live key of the right kind, and that the {@code From} address is one the provider
 * has <em>verified</em> for the domain — an unverified sender is refused at {@code RCPT}/{@code DATA}
 * time, per send. Each of those fails identically in production: {@link SmtpMailTransport#send}
 * swallows it, logs it and answers false, and a client waits for a link that never left.
 *
 * <p><strong>This is the check that survives changing provider</strong>, which the transport it
 * replaced could not: it asserts that whatever is in the environment can send, not that one
 * vendor's API is reachable. Point the variables at Brevo, Resend, Mailgun, Postmark or SES and it
 * proves that one.
 *
 * <p><strong>Opt-in, and it must stay opt-in.</strong> Skipped unless {@code MAIL_LIVE_TEST=true},
 * so {@code mvnw test} and CI never reach the network and never need a credential. Gating on the
 * password's presence alone would be worse: anyone who configures the app to run it would start
 * mailing from their test runs. This sends a real message against a real quota.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * $env:MAIL_LIVE_TEST = "true"
 * $env:EVALOS_MAIL_TEST_TO = "a.mailbox.you.can.open@example.com"
 * .\mvnw.cmd test -Dtest=SmtpMailTransportLiveTest
 * </pre>
 *
 * <p>No credential on the command line: the settings are read from the gitignored {@code .env} the
 * running app is launched with, so this test is configured exactly the way the app is. A real
 * environment variable still wins, matching Spring's own precedence.
 */
@EnabledIfEnvironmentVariable(named = "MAIL_LIVE_TEST", matches = "(?i)true",
		disabledReason = "Live SMTP test: sends a real mail. Set MAIL_LIVE_TEST=true to run it deliberately.")
class SmtpMailTransportLiveTest {

	/** Gitignored, and the file that decides — see its own header. Relative to {@code backend/}. */
	private static final Path ENV_FILE = Path.of("..", ".env");

	private static final String HOST = setting("MAIL_HOST");

	private static final String PORT = setting("MAIL_PORT");

	private static final String USERNAME = setting("MAIL_USERNAME");

	private static final String PASSWORD = setting("MAIL_PASSWORD");

	private static final String FROM = setting("EVALOS_MAIL_FROM");

	/**
	 * Where the proof lands. <strong>Required, with no default, and that is a scar.</strong>
	 *
	 * <p>Its predecessor defaulted to the sender, on the reasoning that it is an address the
	 * account already owns. A sender address is one the provider will send <em>from</em>, which
	 * says nothing about a mailbox existing behind it to send <em>to</em>. The configured sender
	 * was {@code no-reply@}, which had none: the test passed, the message hard-bounced seconds
	 * later, and that account's <em>first ever</em> transactional send bouncing was enough for the
	 * provider to suspend sending pending manual activation. One convenience default cost a
	 * support ticket, and every provider polices new senders this way.
	 *
	 * <p>So: name a real mailbox you can open, deliberately, every time.
	 */
	private static final String RECIPIENT = System.getenv("EVALOS_MAIL_TEST_TO");

	/** Environment first, then the gitignored file, which is Spring Boot's own precedence. */
	private static String setting(String name) {
		String fromEnv = System.getenv(name);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv;
		}
		if (Files.isReadable(ENV_FILE)) {
			try {
				Matcher found = Pattern.compile("(?m)^\\s*" + name + "\\s*=\\s*(.*?)\\s*$")
						.matcher(Files.readString(ENV_FILE, StandardCharsets.UTF_8));
				if (found.find()) {
					return found.group(1).replaceAll("^[\"']|[\"']$", "");
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Cannot read " + ENV_FILE, ex);
			}
		}
		return "";
	}

	/**
	 * A real message leaves, which is the whole assertion.
	 *
	 * <p>The subject carries a nonce so a run is findable in the provider's own log and cannot be
	 * confused with the previous one — the failure this guards against is a send that "worked"
	 * because a stale mail was sitting in the inbox.
	 *
	 * <p><strong>The password is never printed</strong>, only its length. That is what makes a
	 * wrong key diagnosable without copying a credential into a build log.
	 */
	@Test
	void aRealMailLeavesThroughTheConfiguredRelay() {
		assertThat(HOST).describedAs("MAIL_HOST must be set in .env — smtp-relay.brevo.com, "
				+ "smtp.resend.com, smtp.mailgun.org, … see the table over spring.mail in "
				+ "application.yml").isNotBlank();
		assertThat(USERNAME).describedAs("MAIL_USERNAME must be set in .env. It is NOT your account "
				+ "email on every provider: Resend wants the literal 'resend', Brevo wants the SMTP "
				+ "login shown on its SMTP & API page").isNotBlank();
		assertThat(PASSWORD).describedAs("MAIL_PASSWORD must be set in .env").isNotBlank();
		assertThat(FROM).describedAs("EVALOS_MAIL_FROM must be set in .env, and must be an address "
				+ "the provider has verified for the domain").isNotBlank();
		assertThat(RECIPIENT).describedAs("EVALOS_MAIL_TEST_TO must name a REAL mailbox you can open. "
				+ "Never a no-reply address: a hard bounce on a new account can get sending "
				+ "suspended, which is a support ticket, not a retry.").isNotBlank();

		JavaMailSenderImpl relay = new JavaMailSenderImpl();
		relay.setHost(HOST);
		relay.setPort(PORT.isBlank() ? 587 : Integer.parseInt(PORT));
		relay.setUsername(USERNAME);
		relay.setPassword(PASSWORD);
		Properties properties = relay.getJavaMailProperties();
		// The same four the profiles set. Jakarta Mail defaults every timeout to INFINITE, and an
		// unbounded wait here is a test that hangs where production parks a request thread.
		properties.put("mail.smtp.auth", "true");
		properties.put("mail.smtp.starttls.enable", "true");
		properties.put("mail.smtp.connectiontimeout", "5000");
		properties.put("mail.smtp.timeout", "5000");
		properties.put("mail.smtp.writetimeout", "5000");

		SmtpMailTransport transport = new SmtpMailTransport(relay, FROM, HOST);
		assertThat(transport.isConfigured()).isTrue();

		String nonce = UUID.randomUUID().toString().substring(0, 8);
		System.out.printf("[live] smtp %s:%s user=%s password length=%d from=%s -> %s (nonce %s)%n",
				HOST, PORT.isBlank() ? "587" : PORT, USERNAME, PASSWORD.length(), FROM, RECIPIENT, nonce);

		boolean left = transport.send(new MailTransport.Recipient(UUID.randomUUID(), RECIPIENT),
				"EvalOS mail check " + nonce,
				"""
				This is the live SMTP check, not a real notice — nothing is wrong and nothing is \
				required of you.

				If it arrived, the relay, the credentials and the verified sender all work, and \
				client set-password and reset-password mail will leave the same way.
				""");

		// False rather than a throw is the contract: `send` swallows every failure so a mail
		// outage cannot become a 500 on a sign-in. So the logged exception just above is where the
		// reason is, and SMTP states it in the response code: 535 is authentication (wrong key, or
		// a username that is not what this provider means by one), 550/553 on the sender is an
		// unverified or unauthorised From, and a connect timeout on 587 is usually egress — the
		// deploy's IP blocked outbound SMTP, or absent from the provider's authorised-IP list,
		// which is the failure that looks identical to a bad key from inside the application.
		assertThat(left).describedAs("The relay refused the send — see the logged exception just "
				+ "above; its SMTP response code names which of auth, sender or egress failed.")
				.isTrue();
	}
}
