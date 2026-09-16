package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.integration.MailTransport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transport seam: who carries the mail is an environment setting, not a build.
 *
 * <p>The seam exists because the answer has already changed once (SMTP → GHL) and is expected to
 * change again (→ Brevo). What these pin is the three things that make the switch safe — the right
 * one is picked, a wrong name is loud, and a transport that cannot address a particular person
 * says so rather than reporting a send that never happened.
 */
class ClientMailerTest {

	/** A transport that records what it was asked to carry. */
	private static final class Fake implements MailTransport {

		private final String name;

		private final boolean configured;

		private final boolean reachable;

		private Recipient sentTo;

		Fake(String name, boolean configured, boolean reachable) {
			this.name = name;
			this.configured = configured;
			this.reachable = reachable;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public boolean isConfigured() {
			return configured;
		}

		@Override
		public boolean canReach(Recipient to) {
			return reachable;
		}

		@Override
		public boolean send(Recipient to, String subject, String body) {
			this.sentTo = to;
			return true;
		}
	}

	private static final MailTransport.Recipient ANA =
			new MailTransport.Recipient("ana@example.com", "ghl-1");

	@Test
	void theConfiguredTransportIsTheOneThatCarriesIt() {
		Fake smtp = new Fake("smtp", true, true);
		Fake ghl = new Fake("ghl", true, true);

		new ClientMailer(List.of(smtp, ghl), "ghl").sendSetPassword(ANA, "https://portal/set#tok");

		assertThat(ghl.sentTo).isEqualTo(ANA);
		assertThat(smtp.sentTo).isNull();
	}

	/**
	 * <strong>A name matching nothing fails at startup, and names what it found.</strong>
	 *
	 * <p>The alternative — falling back to whichever transport happens to be first — is discovered
	 * by a client who never received their link, weeks later, on an environment nobody is watching.
	 * A typo in a deployment variable should stop the deployment.
	 */
	@Test
	void anUnknownTransportNameRefusesToStart() {
		assertThatThrownBy(() -> new ClientMailer(List.of(new Fake("smtp", true, true)), "brevo"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("brevo")
				.hasMessageContaining("smtp");
	}

	/**
	 * <strong>Configured is not the same as able to reach this person</strong>, and conflating them
	 * is what the GHL transport made dangerous. GHL addresses a contact id; an account whose
	 * sign-up met a GHL outage has none. Reporting a send there would mint a credential token for a
	 * link that never left — and the cooldown would then suppress the retry for a full TTL.
	 */
	@Test
	void aTransportThatCannotAddressThisPersonSendsNothingAndSaysSo() {
		Fake ghl = new Fake("ghl", true, false);
		ClientMailer mailer = new ClientMailer(List.of(ghl), "ghl");

		assertThat(mailer.isConfigured()).isTrue();
		assertThat(mailer.canReach(new MailTransport.Recipient("ana@example.com", null))).isFalse();
		assertThat(mailer.sendSetPassword(ANA, "https://portal/set#tok")).isFalse();
		assertThat(ghl.sentTo).isNull();
	}

	/** An unconfigured transport is the MAIL_UNAVAILABLE path, not a boot failure. */
	@Test
	void anUnconfiguredTransportDegradesRatherThanThrowing() {
		ClientMailer mailer = new ClientMailer(List.of(new Fake("smtp", false, true)), "smtp");

		assertThat(mailer.isConfigured()).isFalse();
		assertThat(mailer.sendResetPassword(ANA, "https://portal/set#tok")).isFalse();
	}
}
