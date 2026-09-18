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

		private String sentHtml;

		@Override
		public boolean send(Recipient to, String subject, String text, String html) {
			this.sentTo = to;
			this.sentHtml = html;
			return true;
		}
	}

	private final com.ie.evalos.service.AuditService audit =
			org.mockito.Mockito.mock(com.ie.evalos.service.AuditService.class);

	private static final java.util.UUID BRAND =
			java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");

	/** Real templates, not a mock: rendering them is part of what these tests exercise. */
	private static final MailTemplates TEMPLATES = new MailTemplates("https://portal.test");

	private static final MailTransport.Recipient ANA =
			new MailTransport.Recipient(BRAND, "ana@example.com");

	@Test
	void theConfiguredTransportIsTheOneThatCarriesIt() {
		Fake smtp = new Fake("smtp", true, true);
		Fake brevo = new Fake("brevo", true, true);

		new ClientMailer(List.of(smtp, brevo), audit, TEMPLATES, "brevo").sendSetPassword(ANA, "Ana", "https://portal/set#tok");

		assertThat(brevo.sentTo).isEqualTo(ANA);
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
		assertThatThrownBy(() -> new ClientMailer(List.of(new Fake("smtp", true, true)), audit, TEMPLATES, "sendgrid"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sendgrid")
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
		Fake brevo = new Fake("brevo", true, false);
		ClientMailer mailer = new ClientMailer(List.of(brevo), audit, TEMPLATES, "brevo");

		assertThat(mailer.isConfigured()).isTrue();
		assertThat(mailer.canReach(new MailTransport.Recipient(BRAND, ""))).isFalse();
		assertThat(mailer.sendSetPassword(ANA, "Ana", "https://portal/set#tok")).isFalse();
		assertThat(brevo.sentTo).isNull();
	}

	/** An unconfigured transport is the MAIL_UNAVAILABLE path, not a boot failure. */
	@Test
	void anUnconfiguredTransportDegradesRatherThanThrowing() {
		ClientMailer mailer = new ClientMailer(List.of(new Fake("smtp", false, true)), audit, TEMPLATES, "smtp");

		assertThat(mailer.isConfigured()).isFalse();
		assertThat(mailer.sendResetPassword(ANA, "Ana", "https://portal/set#tok")).isFalse();
	}

	/**
	 * <strong>A trail that cannot be written does not become an enumeration oracle.</strong>
	 *
	 * <p>{@code recordPortalEvent} is {@code @Transactional} and neither caller of
	 * {@code issueCredential} is, so a transient database error here escaped as a 500 — for a
	 * known address, while an unknown one still answered 204. That difference is exactly the
	 * account-enumeration oracle this class exists to close, arriving by way of the trail instead
	 * of the mail. And because the 500 unwound before {@code credentials.save}, the client was left
	 * holding a link that could never work.
	 *
	 * <p>True rather than false: the message left, which is what this boolean means. The missing
	 * audit row is logged as the real problem it is, and it is not the client's problem.
	 */
	@Test
	void aFailedAuditWriteDoesNotFailTheSendThatAlreadyHappened() {
		Fake brevo = new Fake("brevo", true, true);
		ClientMailer mailer = new ClientMailer(List.of(brevo), audit, TEMPLATES, "brevo");
		org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("pool"))
				.when(audit).recordPortalEvent(org.mockito.ArgumentMatchers.any(),
						org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
						org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
						org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

		assertThat(mailer.sendResetPassword(ANA, "Ana", "https://portal/set#tok")).isTrue();
		assertThat(brevo.sentTo).isEqualTo(ANA);
	}
}
