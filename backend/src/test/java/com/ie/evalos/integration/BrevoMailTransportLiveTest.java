package com.ie.evalos.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that actually sends a mail, and the only thing that can close the Brevo setup.
 *
 * <p><strong>Why nothing cheaper does.</strong> {@code ClientMailerTest} pins the seam with fakes
 * and never touches the wire. What is actually at risk here is everything the wire decides: that
 * the header is {@code api-key} and not {@code Authorization}, that the key is live and its
 * account enabled, that the sender is one Brevo has <em>verified</em> — an unverified one is
 * refused per-send with a 400 — and that a 2xx carries a {@code messageId} the transport can read.
 * Each of those fails the same way in production: {@link BrevoMailTransport#send} swallows it,
 * logs it and answers false, and a client waits for a link that never left. A canned-body test
 * would assert our own belief about all four.
 *
 * <p><strong>Opt-in, and it must stay opt-in.</strong> Skipped unless {@code BREVO_LIVE_TEST=true},
 * so {@code mvnw test} and CI never reach the network and never need a credential. Gating on the
 * key's presence alone would be worse: anyone who sets it up to run the app would start mailing
 * from their test runs. This sends a real message against a real quota.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * $env:BREVO_LIVE_TEST = "true"
 * .\mvnw.cmd test -Dtest=BrevoMailTransportLiveTest
 * </pre>
 *
 * <p>No credential on the command line: the key is read from the gitignored {@code .env} that the
 * running app is launched with, so this test is configured exactly the way the app is. A real
 * environment variable still wins, matching Spring's own precedence.
 */
@EnabledIfEnvironmentVariable(named = "BREVO_LIVE_TEST", matches = "(?i)true",
		disabledReason = "Live Brevo test: sends a real mail. Set BREVO_LIVE_TEST=true to run it deliberately.")
class BrevoMailTransportLiveTest {

	/** Gitignored, and the file that decides — see its own header. Relative to {@code backend/}. */
	private static final Path ENV_FILE = Path.of("..", ".env");

	private static final String API_KEY = setting("EVALOS_MAIL_BREVO_API_KEY");

	private static final String SENDER = setting("EVALOS_MAIL_BREVO_SENDER_EMAIL");

	/**
	 * Where the proof lands. <strong>Required, with no default, and that is a scar.</strong>
	 *
	 * <p>This defaulted to {@link #SENDER} on the reasoning that it is an address the account
	 * already owns, so a first run would need one value set instead of two. That reasoning was
	 * wrong in a way worth keeping: a sender address is an address Brevo will send <em>from</em>,
	 * which says nothing about whether a mailbox exists behind it to send <em>to</em>. The
	 * configured sender was {@code no-reply@}, which is exactly the kind of address that usually
	 * has no mailbox — and did not. The test passed (Brevo accepted the message and returned a
	 * {@code messageId}), the message hard-bounced seconds later, and the account's <em>first
	 * ever</em> transactional send bouncing was enough for Brevo to suspend transactional sending
	 * on the account pending manual activation. One convenience default cost a support ticket.
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
				Matcher found = Pattern.compile("(?m)^\s*" + name + "\s*=\s*(.*?)\s*$")
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
	 * <p>The subject carries a nonce so a run is findable in Brevo's own log and cannot be
	 * confused with the previous one — the failure this guards against is a send that "worked"
	 * because a stale mail was sitting in the inbox.
	 *
	 * <p><strong>The value is never printed</strong>, only its length and prefix. That is what
	 * makes a wrong key diagnosable without copying a credential into a build log.
	 */
	@Test
	void aRealMailLeavesThroughBrevo() {
		assertThat(API_KEY).describedAs("EVALOS_MAIL_BREVO_API_KEY must be set in .env to run this")
				.isNotBlank();
		assertThat(SENDER).describedAs("EVALOS_MAIL_BREVO_SENDER_EMAIL must be set in .env, and must be "
				+ "an address Brevo has verified for the account").isNotBlank();
		assertThat(RECIPIENT).describedAs("EVALOS_MAIL_TEST_TO must name a REAL mailbox you can open. "
				+ "Never a no-reply address: a hard bounce on a low-reputation account can get "
				+ "transactional sending suspended, which is a support ticket, not a retry.")
				.isNotBlank();

		BrevoMailTransport brevo = new BrevoMailTransport("https://api.brevo.com", API_KEY, SENDER,
				"International Evaluations", Duration.ofSeconds(10));

		assertThat(brevo.isConfigured()).isTrue();

		String nonce = UUID.randomUUID().toString().substring(0, 8);
		System.out.printf("[live] brevo key length=%d prefix=%.8s sender=%s -> %s (nonce %s)%n",
				API_KEY.length(), API_KEY, SENDER, RECIPIENT, nonce);

		boolean left = brevo.send(new MailTransport.Recipient(UUID.randomUUID(), RECIPIENT),
				"EvalOS mail check " + nonce,
				"""
				This is the live Brevo check, not a real notice — nothing is wrong and nothing is \
				required of you.

				If it arrived, the key, the verified sender and POST /v3/smtp/email all work, and \
				client set-password and reset-password mail will leave the same way.
				""");

		// False rather than a throw is the contract: `send` swallows every failure so a mail
		// outage cannot become a 500 on a sign-in. So the log line above the failure is where the
		// reason is — a 403 `permission_denied` means Brevo has not activated transactional
		// sending on this account (new accounts, and accounts it has suspended, answer this to
		// every send until a human at Brevo clears them); a 400 usually means an unverified
		// sender; and a 401 means one of THREE
		// things, not one: an `xsmtpsib-` SMTP key where an `xkeysib-` API key belongs, a masked
		// key copied from the dashboard's list view, or — and this one cost a debugging round trip
		// — a caller IP that is not on Brevo's Authorised IPs list. This POST answers 401 with an
		// EMPTY body, so the log cannot tell them apart. `GET https://api.brevo.com/v3/account`
		// with the same key does return the reason, and is the fastest way to split them.
		assertThat(left).describedAs("Brevo refused the send — see the logged error just above. On a "
				+ "401, curl GET https://api.brevo.com/v3/account with the same key: that endpoint "
				+ "returns a reason where this one returns an empty body.")
				.isTrue();
	}
}
