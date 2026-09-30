package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertAccount;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertAccountRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.Test;

/** Unit 72 (D71): the portal policies are accepted once per account, against a version. */
class PortalTermsServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();

	private final ClientAccountRepository clients = mock(ClientAccountRepository.class);
	private final ExpertAccountRepository experts = mock(ExpertAccountRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final PortalTermsService terms = new PortalTermsService(clients, experts, audit);

	private static PortalPrincipal clientByContact(String ghlContactId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.CLIENT, null, ghlContactId, null);
	}

	private static PortalPrincipal expertParty() {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.EXPERT, EXPERT_ID, null, null);
	}

	@Test
	void aFirstSignInMustAcceptAndAcceptingRecordsItOnce() {
		ClientAccount account = mock(ClientAccount.class);
		given(account.getId()).willReturn(UUID.randomUUID());
		given(account.hasAccepted(PortalTermsService.VERSION)).willReturn(false);
		// Most client tokens name the account by its GHL contact, not its id (V44).
		given(clients.findByBrandIdAndGhlContactId(BRAND, "ghl-1")).willReturn(Optional.of(account));

		assertThat(terms.status(clientByContact("ghl-1")).required()).isTrue();

		terms.accept(clientByContact("ghl-1"));
		verify(account).acceptTerms(eq(PortalTermsService.VERSION), any(Instant.class));
		verify(audit).recordPortalEvent(eq(BRAND), eq(PortalAudience.CLIENT), eq("CLIENT_ACCOUNT"), any(),
				eq(AuditAction.TERMS_ACCEPTED), eq(null), eq(Map.of("version", PortalTermsService.VERSION)));
	}

	@Test
	void anAcceptedVersionIsNotAskedAgainNorRecordedTwice() {
		ExpertAccount account = mock(ExpertAccount.class);
		given(account.getBrandId()).willReturn(BRAND);
		given(account.hasAccepted(PortalTermsService.VERSION)).willReturn(true);
		given(experts.findByExpertId(EXPERT_ID)).willReturn(Optional.of(account));

		assertThat(terms.status(expertParty()).required()).isFalse();
		terms.accept(expertParty());

		verify(account, never()).acceptTerms(any(), any());
		verify(audit, never()).recordPortalEvent(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void aNewPolicyVersionAsksAgain() {
		ExpertAccount real = new ExpertAccount(BRAND, EXPERT_ID);
		real.acceptTerms("2020-01-01", Instant.now());
		assertThat(real.hasAccepted("2020-01-01")).isTrue();
		assertThat(real.hasAccepted(PortalTermsService.VERSION)).isFalse();
	}

	/** A pre-Unit 59 per-case link has no account: nothing to ask, nothing to record. */
	@Test
	void aCaseScopedLinkIsNotAsked() {
		PortalPrincipal caseLink = new PortalPrincipal(UUID.randomUUID(), BRAND, UUID.randomUUID(),
				PortalAudience.CLIENT, null, "ghl-1", null);

		assertThat(terms.status(caseLink).required()).isFalse();
		verify(clients, never()).findByBrandIdAndGhlContactId(any(), any());
	}

	@Test
	void anotherBrandsAccountIsNeverTouched() {
		ExpertAccount elsewhere = mock(ExpertAccount.class);
		given(elsewhere.getBrandId()).willReturn(UUID.randomUUID());
		given(experts.findByExpertId(EXPERT_ID)).willReturn(Optional.of(elsewhere));

		terms.accept(expertParty());

		verify(elsewhere, never()).acceptTerms(any(), any());
	}
}
