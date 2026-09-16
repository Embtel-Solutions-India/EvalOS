package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The four answers the sign-in screen branches on, and what each one sends.
 *
 * <p>The one worth reading is {@link #unknownEmailSendsNothing()}: {@code identify} reveals
 * whether an email is known, which is email enumeration and is an accepted decision (spec §3) —
 * but it must not also become a way to make EvalOS send mail to an arbitrary address.
 *
 * <p>{@link #anOutstandingLinkIsNotReissued()} is the other half of that: an email that IS known
 * must not become one either. Both auth routes are unauthenticated, so "known address, unlimited
 * mail" is the same hole at a named inbox.
 *
 * <p><strong>{@code mailer.isConfigured()} AND the send itself are stubbed true wherever mail is
 * expected</strong>, and neither is boilerplate. {@code issueCredential} asks {@code isConfigured}
 * first and mints nothing when the answer is no; it then treats the send's own {@code false} —
 * which is what a failing SMTP host now returns instead of throwing — the same way. A mock left at
 * its default on either is the {@code MAIL_UNAVAILABLE} path rather than the {@code NO_PASSWORD}
 * one, which is the point: an undelivered link is not a link.
 */
class ClientAccountServiceTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);

	private final ClientCredentialTokenRepository credentials = mock(ClientCredentialTokenRepository.class);

	private final ClientMailer mailer = mock(ClientMailer.class);

	private final GhlWriteClient ghlContacts = mock(GhlWriteClient.class);

	private final PortalAccessService links = mock(PortalAccessService.class);

	private final AuditService audit = mock(AuditService.class);

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();

	private final ContactSnapshotService contacts = mock(ContactSnapshotService.class);

	private final ClientAccountService service = new ClientAccountService(accounts, credentials,
			mailer, ghlContacts, contacts, links, audit, encoder, BRAND, Duration.ofMinutes(30),
			"https://client.example.com");

	/**
	 * <strong>Signing up creates the CRM row, which is Unit 44c closing the prospect gap.</strong>
	 *
	 * <p>{@code 00d} §5.4: before this, the only writer of {@code contact_snapshot} was Handoff A,
	 * so it held <em>only contacts that won an opportunity</em> — every prospect, and every lead
	 * Marketing opened this month, was unknown to the portal. The account now points at the row, so
	 * a case can reach the sign-in and the sign-in can reach the cases: the link
	 * {@code .claude/data-model.md} lists as missing.
	 */
	@Test
	void signingUpCreatesTheCrmRowAndLinksTheAccountToIt() {
		UUID contactRow = UUID.randomUUID();
		given(accounts.findByBrandIdAndEmailIgnoreCase(eq(BRAND), any())).willReturn(Optional.empty());
		given(ghlContacts.upsertContact(any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedContact("ghl-c-1", "Ana Okafor", "ana@example.com", null));
		ContactSnapshot snapshot = new ContactSnapshot(BRAND, "ghl-c-1");
		ReflectionTestUtils.setField(snapshot, "id", contactRow);
		given(contacts.findOrCreate(eq(BRAND), any())).willReturn(snapshot);
		given(accounts.saveAndFlush(any())).willAnswer((call) -> call.getArgument(0));

		service.signUp("ana@example.com", "Ana", "Okafor", null);

		ArgumentCaptor<ClientAccount> saved = ArgumentCaptor.forClass(ClientAccount.class);
		verify(accounts).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getContactId()).isEqualTo(contactRow);
		assertThat(saved.getValue().getGhlContactId()).isEqualTo("ghl-c-1");

		ArgumentCaptor<ContactSnapshotService.Details> details =
				ArgumentCaptor.forClass(ContactSnapshotService.Details.class);
		verify(contacts).findOrCreate(eq(BRAND), details.capture());
		// GHL's id goes with it, so the row is matched on the identity GHL owns rather than on an
		// address two people at a firm might share.
		assertThat(details.getValue().ghlContactId()).isEqualTo("ghl-c-1");
		assertThat(details.getValue().fullName()).isEqualTo("Ana Okafor");
	}

	/**
	 * A CRM row that cannot be written does not cost the client their sign-up.
	 *
	 * <p>{@code client_account.contact_id} is nullable exactly for this: the account is the thing
	 * the person just created and the link is a convenience EvalOS can repair later. Failing the
	 * sign-up over it would be the wrong thing to fail on.
	 */
	@Test
	void aFailedCrmRowStillSignsThemUp() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(eq(BRAND), any())).willReturn(Optional.empty());
		given(ghlContacts.upsertContact(any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedContact("ghl-c-1", "Ana", "ana@example.com", null));
		given(contacts.findOrCreate(any(), any())).willThrow(new IllegalStateException("database said no"));
		given(accounts.saveAndFlush(any())).willAnswer((call) -> call.getArgument(0));

		service.signUp("ana@example.com", "Ana", null, null);

		ArgumentCaptor<ClientAccount> saved = ArgumentCaptor.forClass(ClientAccount.class);
		verify(accounts).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getContactId()).isNull();
		assertThat(saved.getValue().getGhlContactId()).isEqualTo("ghl-c-1");
	}

	@Test
	void anAccountWithAPasswordAnswersPasswordSet() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.PASSWORD_SET);
		verify(mailer, never()).sendSetPassword(any(), any());
	}

	@Test
	void aSeededAccountAnswersNoPasswordAndIsSentASetLink() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendSetPassword(any(), any())).willReturn(true);
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.NO_PASSWORD);
		verify(mailer).sendSetPassword(eq("ana@example.com"), any());
	}

	@Test
	void unknownEmailSendsNothing() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com"))
				.willReturn(Optional.empty());

		assertThat(service.identify("nobody@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.UNKNOWN);
		verify(mailer, never()).sendSetPassword(any(), any());
		verify(credentials, never()).save(any());
	}

	@Test
	void emailIsMatchedCaseInsensitively() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ANA@Example.com"))
				.willReturn(Optional.of(account));

		assertThat(service.identify("  ANA@Example.com  "))
				.isEqualTo(ClientAccountService.IdentifyState.PASSWORD_SET);
	}

	@Test
	void signInWithTheRightPasswordMintsAToken() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));
		given(links.mintForClientAccount(account)).willReturn(
				new PortalAccessService.MintedToken("tok",
						java.time.Instant.now().plusSeconds(600)));

		// The bare token, not a URL with the token buried in its fragment.
		assertThat(service.signIn("ana@example.com", "Correct!1").token()).isEqualTo("tok");
		// objectId is any(): ScopedEntity generates the id at persist time, so getId() is null on
		// an entity built with `new`. eq(account.getId()) would silently become eq(null) and pass
		// for the wrong reason. What is worth pinning is the action and the brand.
		verify(audit).recordPortalEvent(eq(BRAND), any(), eq("CLIENT_ACCOUNT"), any(),
				eq(com.ie.evalos.domain.AuditAction.CLIENT_SIGNED_IN), any(), any());
	}

	@Test
	void signInWithTheWrongPasswordIsRefusedAndAudited() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.signIn("ana@example.com", "Wrong!1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
		verify(audit).recordPortalEvent(eq(BRAND), any(), eq("CLIENT_ACCOUNT"), any(),
				eq(com.ie.evalos.domain.AuditAction.CLIENT_SIGN_IN_REFUSED), any(), any());
		verify(links, never()).mintForClientAccount(any());
	}

	@Test
	void signInToAnAccountWithNoPasswordIsRefusedWithoutComparingAHash() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));
		// A real BCryptPasswordEncoder tolerates matches(raw, null) — it returns false rather than
		// throwing, so it cannot prove hasPassword() is checked first. A hostile encoder that throws
		// on any call to matches() makes the ordering load-bearing: if signIn ever called matches()
		// before hasPassword(), this test would see IllegalArgumentException, not the
		// InvalidRequestException a refusal is supposed to be.
		PasswordEncoder hostile = mock(PasswordEncoder.class);
		given(hostile.matches(any(), any())).willThrow(new IllegalArgumentException("must not be called"));
		ClientAccountService hostileService = new ClientAccountService(accounts, credentials, mailer,
				ghlContacts, contacts, links, audit, hostile, BRAND, Duration.ofMinutes(30),
				"https://client.example.com");

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> hostileService.signIn("ana@example.com", "anything"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}

	@Test
	void forgotPasswordForAnUnknownEmailIsSilentAndSendsNothing() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com"))
				.willReturn(Optional.empty());

		service.forgotPassword("nobody@example.com");

		verify(mailer, never()).sendResetPassword(any(), any());
	}

	@Test
	void withMailUnconfiguredASeededAccountIsToldToContactUsAndNoTokenIsMinted() {
		// isConfigured() is left at the mock's default false.
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.MAIL_UNAVAILABLE);
		// NO_PASSWORD would put "we've emailed you a link" on screen with nothing sent, and the
		// client would wait forever. The row is not minted either: a link with no way of reaching
		// anybody is a row that can only expire.
		verify(credentials, never()).save(any());
		verify(mailer, never()).sendSetPassword(any(), any());
	}

	/**
	 * A configured mailer whose send fails answers {@code MAIL_UNAVAILABLE} and leaves no row.
	 *
	 * <p><strong>The absent {@code credentials.save} is the assertion that matters</strong>, and it
	 * is why the send happens before the write rather than after. Save-then-send would leave an
	 * unspent token behind, and {@link #anOutstandingLinkIsNotReissued()} above would then read it
	 * as "a link is already on its way" — so the client would be told to check an inbox nothing
	 * ever reached, told it again for a full TTL, and have their retry suppressed by the very
	 * failure they are retrying.
	 */
	@Test
	void aFailedSendAnswersMailUnavailableAndLeavesNoTokenToPoisonTheCooldown() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendSetPassword(any(), any())).willReturn(false);
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.MAIL_UNAVAILABLE);
		verify(mailer).sendSetPassword(eq("ana@example.com"), any());
		verify(credentials, never()).save(any());
	}

	/**
	 * A failing mail host must not make {@code forgotPassword} differentiate.
	 *
	 * <p>It used to: {@code MailException} is unchecked, so a <strong>known</strong> address threw
	 * its way to a 500 while an unknown one still answered 204 — turning the one method written
	 * not to leak into an enumeration oracle, on exactly the day somebody is probing. The
	 * assertion is that this does not throw; {@code ClientAuthControllerTest} holds the other half
	 * (both addresses, byte-for-byte the same 204).
	 */
	@Test
	void forgotPasswordDoesNotDifferentiateWhenTheMailHostIsDown() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendResetPassword(any(), any())).willReturn(false);
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));

		service.forgotPassword("ana@example.com");

		verify(credentials, never()).save(any());
	}

	@Test
	void anOutstandingLinkIsNotReissued() {
		given(mailer.isConfigured()).willReturn(true);
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));
		given(credentials.findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
				any(), eq(CredentialPurpose.SET), any()))
				.willReturn(Optional.of(new ClientCredentialToken(BRAND, UUID.randomUUID(),
						PortalAccessService.hash("already-sent"), CredentialPurpose.SET,
						Instant.now().plusSeconds(600))));

		// Still NO_PASSWORD: a working link IS in that inbox, which is what the copy says.
		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.NO_PASSWORD);
		verify(credentials, never()).save(any());
		verify(mailer, never()).sendSetPassword(any(), any());
	}

	@Test
	void forgotPasswordForAKnownEmailMintsAResetTokenAndMailsTheLink() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendResetPassword(any(), any())).willReturn(true);
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));

		service.forgotPassword("ana@example.com");

		ArgumentCaptor<ClientCredentialToken> saved = ArgumentCaptor.forClass(ClientCredentialToken.class);
		verify(credentials).save(saved.capture());
		// RESET, not SET. The two purposes are what the cooldown finder partitions on and what
		// decides which of the two mails is sent; a token minted as SET here would let a reset
		// request consume the set-password allowance and arrive with the wrong words in it.
		assertThat(saved.getValue().getPurpose()).isEqualTo(CredentialPurpose.RESET);

		ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
		verify(mailer).sendResetPassword(eq("ana@example.com"), link.capture());
		verify(mailer, never()).sendSetPassword(any(), any());
		// The token rides in the FRAGMENT of the set-password route, like every other portal
		// credential — never a query parameter, which lands in access logs and Referer headers.
		// On the CLIENT PORTAL's own origin (`evalos.portal.client-base-url`). This is now the only
		// client-facing URL EvalOS composes: the property it used to be built from, `base-url`,
		// pointed at the staff SPA and has since been deleted along with the screen it served.
		assertThat(link.getValue()).startsWith("https://client.example.com/set-password#");
	}

	@Test
	void aSetPasswordLinkForAnotherBrandsAccountIsRefused() {
		UUID accountId = UUID.randomUUID();
		UUID otherBrand = UUID.fromString("22222222-2222-2222-2222-222222222222");
		ClientCredentialToken token = new ClientCredentialToken(otherBrand, accountId,
				PortalAccessService.hash("tok"), CredentialPurpose.SET, Instant.now().plusSeconds(600));
		given(credentials.findByTokenHash(PortalAccessService.hash("tok"))).willReturn(Optional.of(token));
		given(accounts.findById(accountId))
				.willReturn(Optional.of(new ClientAccount(otherBrand, "ana@example.com")));

		// setPassword is the only path that reaches an account through the token's own FK rather
		// than through a brand-scoped finder, so the check has to be explicit here.
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.setPassword("tok", "Brand!New1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
		verify(links, never()).mintForClientAccount(any());
	}

	@Test
	void aUsedSetPasswordTokenIsRefusedTheSecondTime() {
		// An EXPLICIT id, not account.getId(): ScopedEntity generates ids at persist time, so
		// getId() is null here and both the token's FK and the findById stub would be null —
		// the test would pass by matching null against null rather than by linking the two.
		UUID accountId = UUID.randomUUID();
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		com.ie.evalos.domain.ClientCredentialToken token = new com.ie.evalos.domain.ClientCredentialToken(
				BRAND, accountId, PortalAccessService.hash("tok"),
				com.ie.evalos.domain.CredentialPurpose.SET,
				java.time.Instant.now().plusSeconds(600));
		given(credentials.findByTokenHash(PortalAccessService.hash("tok")))
				.willReturn(Optional.of(token));
		given(accounts.findById(accountId)).willReturn(Optional.of(account));
		given(links.mintForClientAccount(account)).willReturn(
				new PortalAccessService.MintedToken("tok",
						java.time.Instant.now().plusSeconds(600)));

		service.setPassword("tok", "Brand!New1");

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.setPassword("tok", "Another!1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}

	/**
	 * The gap this route closes: before 2026-09-15 nothing created a {@code client_account} at
	 * runtime, so a client acquired after V45's backfill was told "we couldn't find that email"
	 * for ever. Signing up must therefore actually write the row — and link the contact, because
	 * an account with none is a lead no salesperson can see.
	 */
	@Test
	void aStrangerGetsAnAccountLinkedToTheContactGhlReturns() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendSetPassword(any(), any())).willReturn(true);
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));
		given(ghlContacts.upsertContact("Ana", "Okafor", "ana@example.com", "+15550100"))
				.willReturn(new GhlWriteClient.UpsertedContact("ghl-1", "Ana Okafor",
						"ana@example.com", "+15550100"));
		// Absent before the insert, present after it — identify() re-reads through the same finder.
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		assertThat(service.signUp("ana@example.com", "Ana", "Okafor", "+15550100"))
				.isEqualTo(ClientAccountService.IdentifyState.NO_PASSWORD);

		ArgumentCaptor<ClientAccount> saved = ArgumentCaptor.forClass(ClientAccount.class);
		verify(accounts).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getGhlContactId()).isEqualTo("ghl-1");
		assertThat(saved.getValue().getEmail()).isEqualTo("ana@example.com");
		assertThat(saved.getValue().hasPassword()).isFalse();
		verify(mailer).sendSetPassword(eq("ana@example.com"), any());
	}

	/**
	 * <strong>Signing up with an address we already hold must not create a second account, and
	 * must not touch GHL.</strong> It is the most ordinary mistake a person makes — they cannot
	 * remember whether they registered — and the wrong answer is two contacts for one client,
	 * which invariant 7 exists to prevent.
	 */
	@Test
	void anAddressWeAlreadyHoldCreatesNothingAndAnswersLikeSignIn() {
		ClientAccount existing = new ClientAccount(BRAND, "ana@example.com");
		existing.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(existing));

		assertThat(service.signUp("ana@example.com", "Ana", "Okafor", null))
				.isEqualTo(ClientAccountService.IdentifyState.PASSWORD_SET);

		verify(ghlContacts, never()).upsertContact(any(), any(), any(), any());
		verify(accounts, never()).saveAndFlush(any());
		verify(mailer, never()).sendSetPassword(any(), any());
	}

	/**
	 * <strong>Signing up never returns a session, and this is the test that says why.</strong>
	 * The address may be one GHL already holds — a client Sales logged last week, whose cases sit
	 * behind it — so a token here would be account takeover by typing a stranger's email. Control
	 * of the mailbox is proved by the set-password link and by nothing else.
	 */
	@Test
	void signingUpMintsNoToken() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendSetPassword(any(), any())).willReturn(true);
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));
		given(ghlContacts.upsertContact(any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedContact("ghl-1", null, "ana@example.com", null));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		service.signUp("ana@example.com", null, null, null);

		verify(links, never()).mintForClientAccount(any());
	}

	/**
	 * A double-submitted form races on {@code client_account_brand_email_key}. The loser must
	 * answer what the winner's row says, not 500 on the front door.
	 */
	@Test
	void aRacedSecondSubmissionAnswersRatherThanFailing() {
		given(mailer.isConfigured()).willReturn(true);
		given(mailer.sendSetPassword(any(), any())).willReturn(true);
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));
		given(ghlContacts.upsertContact(any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedContact("ghl-1", null, "ana@example.com", null));
		given(accounts.saveAndFlush(any()))
				.willThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		assertThat(service.signUp("ana@example.com", null, null, null))
				.isEqualTo(ClientAccountService.IdentifyState.NO_PASSWORD);
	}
}
