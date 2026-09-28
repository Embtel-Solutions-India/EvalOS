package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertAccount;
import com.ie.evalos.domain.ExpertCredentialToken;
import com.ie.evalos.repository.ExpertAccountRepository;
import com.ie.evalos.repository.ExpertCredentialTokenRepository;
import com.ie.evalos.repository.ExpertRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Expert sign-up and sign-in (Unit 59, D23): the roster decides, the account is bound to the roster
 * row, and nothing about the answer tells a caller who is on the panel.
 */
class ExpertAccountServiceTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final ExpertRepository experts = mock(ExpertRepository.class);

	private final ExpertAccountRepository accounts = mock(ExpertAccountRepository.class);

	private final ExpertCredentialTokenRepository credentials = mock(ExpertCredentialTokenRepository.class);

	private final ClientMailer mailer = mock(ClientMailer.class);

	private final PortalAccessService links = mock(PortalAccessService.class);

	private final AuditService audit = mock(AuditService.class);

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();

	private final ExpertAccountService service = new ExpertAccountService(experts, accounts, credentials, mailer,
			links, audit, encoder, BRAND, Duration.ofMinutes(30), "https://experts.example.com/");

	private Expert onRoster(String email) {
		Expert expert = new Expert(BRAND, "Dr Ada Byron");
		ReflectionTestUtils.setField(expert, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(expert, "email", email);
		given(experts.findByBrandIdAndEmailIgnoreCase(BRAND, email)).willReturn(Optional.of(expert));
		return expert;
	}

	private ExpertAccount account(Expert expert, String password) {
		ExpertAccount account = new ExpertAccount(BRAND, expert.getId());
		ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
		if (password != null) {
			account.setPasswordHash(encoder.encode(password));
		}
		given(accounts.findByExpertId(expert.getId())).willReturn(Optional.of(account));
		return account;
	}

	@Test
	void aKnownExpertIsSentASetPasswordLinkAndTheAccountIsOpened() {
		Expert expert = onRoster("ada@example.com");
		ExpertAccount opened = new ExpertAccount(BRAND, expert.getId());
		ReflectionTestUtils.setField(opened, "id", UUID.randomUUID());
		given(accounts.findByExpertId(expert.getId())).willReturn(Optional.empty());
		given(accounts.saveAndFlush(any())).willReturn(opened);
		given(mailer.canReach(any())).willReturn(true);
		given(mailer.sendExpertLink(any(), any(), anyString(), anyBoolean())).willReturn(true);

		service.sendLink(" ada@example.com ");

		ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
		verify(mailer).sendExpertLink(any(), eq("Dr Ada Byron"), link.capture(), eq(false));
		assertThat(link.getValue()).startsWith("https://experts.example.com/set-password#");
		ArgumentCaptor<ExpertCredentialToken> saved = ArgumentCaptor.forClass(ExpertCredentialToken.class);
		verify(credentials).save(saved.capture());
		assertThat(saved.getValue().getExpertAccountId()).isEqualTo(opened.getId());
		assertThat(saved.getValue().getPurpose()).isEqualTo(CredentialPurpose.SET);
	}

	@Test
	void anExpertWithAPasswordIsSentAResetLinkInstead() {
		Expert expert = onRoster("ada@example.com");
		account(expert, "Correct!1");
		given(mailer.canReach(any())).willReturn(true);
		given(mailer.sendExpertLink(any(), any(), anyString(), anyBoolean())).willReturn(true);

		service.sendLink("ada@example.com");

		verify(mailer).sendExpertLink(any(), any(), anyString(), eq(true));
	}

	/** Q6b: an unknown email gets no mail and no account — experts sign up only once hired. */
	@Test
	void anUnknownEmailSendsNothingAndOpensNoAccount() {
		given(experts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com")).willReturn(Optional.empty());

		service.sendLink("nobody@example.com");

		verifyNoInteractions(mailer, accounts, credentials);
	}

	@Test
	void anOutstandingLinkIsNotReissued() {
		Expert expert = onRoster("ada@example.com");
		ExpertAccount account = account(expert, null);
		given(mailer.canReach(any())).willReturn(true);
		given(credentials.findFirstByExpertAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(eq(account.getId()),
				eq(CredentialPurpose.SET), any())).willReturn(Optional.of(mock(ExpertCredentialToken.class)));

		service.sendLink("ada@example.com");

		verify(mailer, never()).sendExpertLink(any(), any(), anyString(), anyBoolean());
	}

	@Test
	void signingInMintsForTheBoundExpert() {
		Expert expert = onRoster("ada@example.com");
		ExpertAccount account = account(expert, "Correct!1");
		given(links.mintForExpertAccount(account)).willReturn(new PortalAccessService.MintedToken("t", Instant.now()));

		assertThat(service.signIn("ada@example.com", "Correct!1").token()).isEqualTo("t");
		assertThat(account.getLastSignInAt()).isNotNull();
	}

	@Test
	void everySignInRefusalIsTheSameSentence() {
		Expert withPassword = onRoster("ada@example.com");
		account(withPassword, "Correct!1");
		Expert noAccount = onRoster("grace@example.com");
		given(accounts.findByExpertId(noAccount.getId())).willReturn(Optional.empty());
		given(experts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com")).willReturn(Optional.empty());

		String wrongPassword = message(() -> service.signIn("ada@example.com", "wrong"));
		assertThat(message(() -> service.signIn("grace@example.com", "Correct!1"))).isEqualTo(wrongPassword);
		assertThat(message(() -> service.signIn("nobody@example.com", "Correct!1"))).isEqualTo(wrongPassword);
		verify(links, never()).mintForExpertAccount(any());
	}

	@Test
	void aLinkWorksOnceAndOnlyInThisBrand() {
		Expert expert = onRoster("ada@example.com");
		ExpertAccount account = account(expert, null);
		given(accounts.findById(account.getId())).willReturn(Optional.of(account));
		ExpertCredentialToken link = new ExpertCredentialToken(BRAND, account.getId(), PortalAccessService.hash("tok"),
				CredentialPurpose.SET, Instant.now().plusSeconds(600));
		given(credentials.findByTokenHash(PortalAccessService.hash("tok"))).willReturn(Optional.of(link));
		given(links.mintForExpertAccount(account)).willReturn(new PortalAccessService.MintedToken("t", Instant.now()));

		service.setPassword("tok", "Correct!1");
		assertThat(encoder.matches("Correct!1", account.getPasswordHash())).isTrue();
		assertThatThrownBy(() -> service.setPassword("tok", "Another!1")).isInstanceOf(InvalidRequestException.class);

		ExpertCredentialToken otherBrand = new ExpertCredentialToken(UUID.randomUUID(), account.getId(),
				PortalAccessService.hash("other"), CredentialPurpose.SET, Instant.now().plusSeconds(600));
		given(credentials.findByTokenHash(PortalAccessService.hash("other"))).willReturn(Optional.of(otherBrand));
		assertThatThrownBy(() -> service.setPassword("other", "Correct!1")).isInstanceOf(InvalidRequestException.class);
	}

	private static String message(Runnable call) {
		try {
			call.run();
		}
		catch (InvalidRequestException refused) {
			return refused.getMessage();
		}
		throw new AssertionError("expected a refusal");
	}
}
