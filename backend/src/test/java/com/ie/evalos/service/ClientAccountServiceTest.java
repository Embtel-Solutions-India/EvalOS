package com.ie.evalos.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.junit.jupiter.api.Test;
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
 * The three answers the sign-in screen branches on, and what each one sends.
 *
 * <p>The one worth reading is {@link #unknownEmailSendsNothing()}: {@code identify} reveals
 * whether an email is known, which is email enumeration and is an accepted decision (spec §3) —
 * but it must not also become a way to make EvalOS send mail to an arbitrary address.
 */
class ClientAccountServiceTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);

	private final ClientCredentialTokenRepository credentials = mock(ClientCredentialTokenRepository.class);

	private final ClientMailer mailer = mock(ClientMailer.class);

	private final PortalAccessService links = mock(PortalAccessService.class);

	private final AuditService audit = mock(AuditService.class);

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();

	private final ClientAccountService service = new ClientAccountService(accounts, credentials,
			mailer, links, audit, encoder, BRAND, Duration.ofMinutes(30), "https://portal.example.com");

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
				new PortalAccessService.MintedLink("https://portal.example.com/#tok",
						java.time.Instant.now().plusSeconds(600)));

		assertThat(service.signIn("ana@example.com", "Correct!1").url()).contains("#tok");
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
		ClientAccountService hostileService = new ClientAccountService(accounts, credentials, mailer, links,
				audit, hostile, BRAND, Duration.ofMinutes(30), "https://portal.example.com");

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
				new PortalAccessService.MintedLink("https://portal.example.com/#tok",
						java.time.Instant.now().plusSeconds(600)));

		service.setPassword("tok", "Brand!New1");

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.setPassword("tok", "Another!1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}
}
