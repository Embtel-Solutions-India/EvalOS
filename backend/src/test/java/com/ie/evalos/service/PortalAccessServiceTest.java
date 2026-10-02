package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.PortalAccessRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The token model of Unit 14: what is stored, what is returned, and what stops working when.
 *
 * <p>The repository is mocked, so what these hold is the <em>service's</em> rules — the token is
 * never stored, re-minting revokes, and three kinds of bad token are one answer. The unique index
 * behind them is exercised against real Postgres in {@code LocalPostgresIntegrationTest}.
 */
class PortalAccessServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID PM = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();

	private final PortalAccessRepository tokens = mock(PortalAccessRepository.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);

	/** Signing in is the only mint left (Unit 59): staff-minted expert links were removed. */
	private final PortalAccessService links = new PortalAccessService(tokens, Duration.ofDays(7));

	private Case subject;

	@BeforeEach
	void aCaseWithADraftWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
		// Every mint on this service names an expert now, so the fixture carries one. The one test
		// that asserts the refusal clears it.
		subject.setExpertId(EXPERT_ID);
		given(lifecycle.load(CASE_ID)).willReturn(subject);
		given(tokens.save(any(PortalAccess.class))).willAnswer(call -> call.getArgument(0));
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), any())).willReturn(List.of());

		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new StaffPrincipal(PM, "pm@evalos.local", "Priya Menon", Role.PROJECT_MANAGER, BRAND, null, null, true),
				null, List.of()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	// --- Unit 35, D1: the party-scoped credential -----------------------------

	/**
	 * Unknown, expired and revoked are one answer, so nothing about which it was is learnable.
	 * The four cases are asserted together because the property is that they are
	 * <em>indistinguishable</em>, not that each fails.
	 */
	@Test
	void everyKindOfBadTokenResolvesToTheSameNothing() {
		String token = "a-token-somebody-was-given";
		PortalAccess expired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null,
				PortalAccessService.hash(token), Instant.now().minus(Duration.ofDays(1)));
		PortalAccess revoked = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null,
				PortalAccessService.hash(token), Instant.now().plus(Duration.ofDays(1)));
		revoked.revoke(Instant.now());

		given(tokens.findByTokenHash(any())).willReturn(Optional.empty());
		assertThat(links.resolve(token)).isEmpty();
		assertThat(links.resolve(null)).isEmpty();
		assertThat(links.resolve("  ")).isEmpty();

		given(tokens.findByTokenHash(PortalAccessService.hash(token))).willReturn(Optional.of(expired));
		assertThat(links.resolve(token)).isEmpty();

		given(tokens.findByTokenHash(PortalAccessService.hash(token))).willReturn(Optional.of(revoked));
		assertThat(links.resolve(token)).isEmpty();
	}

	/**
	 * <strong>Signing out ends the token on the server</strong> (Unit 75, D72): it stops resolving at
	 * once instead of living out its seven days, and signing out again is a no-op.
	 */
	@Test
	void signingOutRevokesTheTokenAndASecondSignOutIsHarmless() {
		String token = "a-signed-in-token";
		PortalAccess live = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null,
				PortalAccessService.hash(token), Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByTokenHash(PortalAccessService.hash(token))).willReturn(Optional.of(live));

		links.revoke(token);

		assertThat(live.getRevokedAt()).isNotNull();
		assertThat(links.resolve(token)).isEmpty();
		Instant first = live.getRevokedAt();
		links.revoke(token);
		links.revoke(null);
		assertThat(live.getRevokedAt()).isEqualTo(first);
		verify(tokens).save(live);
	}

	/** A live token yields the principal, and using it moves last-seen — the field support needs. */
	@Test
	void aLiveTokenResolvesToItsOwnCaseAndStampsLastSeen() {
		String token = "a-live-token";
		PortalAccess live = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null,
				PortalAccessService.hash(token), Instant.now().plus(Duration.ofDays(1)));
		given(tokens.findByTokenHash(PortalAccessService.hash(token))).willReturn(Optional.of(live));

		Optional<PortalPrincipal> resolved = links.resolve(token);

		assertThat(resolved).get().satisfies(principal -> {
			assertThat(principal.brandId()).isEqualTo(BRAND);
			assertThat(principal.caseId()).isEqualTo(CASE_ID);
			assertThat(principal.audience()).isEqualTo(PortalAudience.CLIENT);
		});
		assertThat(live.getLastSeenAt()).isNotNull();
		verify(tokens).save(live);
	}

	/**
	 * <strong>Sign-in mints the credential that already exists.</strong> A verified password hands
	 * back the same party-scoped token the staff mint button issues, so every screen behind
	 * {@code PortalTokenFilter} keeps working without knowing an account exists — and re-minting
	 * revokes the previous one, exactly as every other mint on this service does.
	 */
	@Test
	void mintForClientAccountIssuesAPartyTokenAndRetiresThePrevious() {
		UUID brand = UUID.randomUUID();
		ClientAccount account = persisted(new ClientAccount(brand, "ana@example.com"));
		account.linkGhlContact("ghl-contact-1");
		PortalAccess previous = PortalAccess.forParty(brand, PortalAudience.CLIENT, "ghl-contact-1",
				null, "old-hash", Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brand, "ghl-contact-1", PortalAudience.CLIENT)).willReturn(List.of(previous));

		PortalAccessService.MintedToken issued = links.mintForClientAccount(account);

		// **A bare token, not a URL.** The client is already on the portal a link would have sent
		// them to; building one here only for the controller to strip it back was the ceremony this
		// replaced. Same generator as every other mint: 256 bits of base64url.
		assertThat(issued.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(previous.getRevokedAt()).isNotNull();

		PortalAccess minted = savedAccess();
		assertThat(minted.matches(PortalAccessService.hash(issued.token()))).isTrue();
		assertThat(minted.getGhlContactId()).isEqualTo("ghl-contact-1");
		assertThat(minted.getClientAccountId()).isNull();
		assertThat(minted.isPartyScoped()).isTrue();
	}

	/**
	 * <strong>An account with no GHL contact still mints</strong>, scoped to the account instead —
	 * the normal case after the 2026-09-11 CRM replacement, and the row {@code V44} widened the
	 * scope constraint to admit. The previous account-scoped token is retired for the same reason
	 * every other shape's is: V44's partial index allows exactly one live row per account.
	 */
	@Test
	void mintForClientAccountWithNoGhlContactScopesTheTokenToTheAccount() {
		UUID brand = UUID.randomUUID();
		ClientAccount account = persisted(new ClientAccount(brand, "ana@example.com"));
		PortalAccess previous = PortalAccess.forAccount(brand, account.getId(), "old-hash",
				Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByClientAccountIdOrderByCreatedAtDesc(account.getId()))
				.willReturn(List.of(previous));

		PortalAccessService.MintedToken issued = links.mintForClientAccount(account);

		assertThat(issued.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(previous.getRevokedAt()).isNotNull();
		verify(tokens, never()).findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				any(), any(), any());

		PortalAccess minted = savedAccess();
		assertThat(minted.getGhlContactId()).isNull();
		assertThat(minted.getClientAccountId()).isNotNull().isEqualTo(account.getId());
		assertThat(minted.getAudience()).isEqualTo(PortalAudience.CLIENT);
		assertThat(minted.isPartyScoped()).isTrue();
	}

	/**
	 * An account-scoped row resolves like any other party token: {@code isPartyScoped}, and a null
	 * contact id. That null is the whole downstream contract — {@code PortalCaseService} fails
	 * closed on it and the GHL-backed reads answer empty rather than calling GHL with nothing.
	 */
	@Test
	void anAccountScopedTokenResolvesToAPartyPrincipalWithNoContact() {
		UUID brand = UUID.randomUUID();
		PortalAccess access = PortalAccess.forAccount(brand, UUID.randomUUID(),
				PortalAccessService.hash("tok"), Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByTokenHash(PortalAccessService.hash("tok"))).willReturn(Optional.of(access));

		PortalPrincipal principal = links.resolve("tok").orElseThrow();

		assertThat(principal.isPartyScoped()).isTrue();
		assertThat(principal.ghlContactId()).isNull();
		assertThat(principal.audience()).isEqualTo(PortalAudience.CLIENT);
		assertThat(principal.brandId()).isEqualTo(brand);
	}

	/**
	 * <strong>A shape change does not leave the old shape's credential live.</strong> A client who
	 * signs in before Unit 43 pushes them to GHL holds an account-scoped token; linking the contact
	 * moves the next mint to the contact shape, and retiring only within that shape would leave the
	 * first token working for the rest of its seven days. "Re-minting revokes the previous" is
	 * stated as an invariant of this service in three places, and this is the sequence that would
	 * have made it false.
	 */
	@Test
	void mintForClientAccountRetiresTheAccountTokenEvenWhenTheShapeChanges() {
		UUID brand = UUID.randomUUID();
		ClientAccount account = persisted(new ClientAccount(brand, "ana@example.com"));
		PortalAccess contactless = PortalAccess.forAccount(brand, account.getId(), "old-hash",
				Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByClientAccountIdOrderByCreatedAtDesc(account.getId()))
				.willReturn(List.of(contactless));

		account.linkGhlContact("ghl-contact-1");
		links.mintForClientAccount(account);

		assertThat(contactless.getRevokedAt()).isNotNull();
		assertThat(savedAccess().getGhlContactId()).isEqualTo("ghl-contact-1");
	}

	/**
	 * A transient account names nobody, the way a case with no contact does. The database would
	 * refuse the row at commit ({@code V44}), but as a 500 — and Task 4's "create the account, then
	 * mint" flow is one missing flush away from producing one.
	 */
	@Test
	void mintForClientAccountRefusesAnUnsavedAccount() {
		assertThatThrownBy(() -> links.mintForClientAccount(
				new ClientAccount(UUID.randomUUID(), "ana@example.com")))
				.isInstanceOf(IllegalTransitionException.class);

		verify(tokens, never()).save(any());
	}

	/** The id a persisted entity would carry: Hibernate assigns it at persist, so nothing else does. */
	private static ClientAccount persisted(ClientAccount account) {
		ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
		return account;
	}

	/** The row this mint wrote, which is the only way to see what was actually scoped. */
	private PortalAccess savedAccess() {
		org.mockito.ArgumentCaptor<PortalAccess> saved =
				org.mockito.ArgumentCaptor.forClass(PortalAccess.class);
		verify(tokens, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		return saved.getAllValues().get(saved.getAllValues().size() - 1);
	}
}
