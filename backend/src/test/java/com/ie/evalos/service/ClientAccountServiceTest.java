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
		given(mailer.isConfigured()).willReturn(true);
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
}
