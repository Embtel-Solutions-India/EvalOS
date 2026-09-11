package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.PortalAccessRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

	private final PortalAccessRepository tokens = mock(PortalAccessRepository.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final AuditService audit = mock(AuditService.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);

	private final PortalAccessService links = new PortalAccessService(
			tokens, lifecycle, contacts, audit, Duration.ofDays(30), Duration.ofDays(7),
			"https://portal.evalos.test/", "https://experts.evalos.test");

	private Case subject;

	@BeforeEach
	void aCaseWithADraftWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
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

	private PortalAccess minted() {
		links.mint(CASE_ID, PortalAudience.CLIENT);
		org.mockito.ArgumentCaptor<PortalAccess> saved = org.mockito.ArgumentCaptor.forClass(PortalAccess.class);
		verify(tokens).save(saved.capture());
		return saved.getValue();
	}

	/**
	 * The property the whole model rests on: a database read yields no working link.
	 *
	 * <p>Asserted by taking the URL apart — the token is in the <strong>fragment</strong>, and the
	 * row must not contain it in any form. The fragment matters as well as the hashing: a query
	 * parameter would land in access logs and {@code Referer} headers on the way to the server.
	 */
	@Test
	void theTokenIsReturnedOnceAndStoredOnlyAsAHash() {
		PortalAccessService.MintedLink link = links.mint(CASE_ID, PortalAudience.CLIENT);

		assertThat(link.url()).startsWith("https://portal.evalos.test/portal/client#");
		String token = link.url().substring(link.url().indexOf('#') + 1);
		// 256 bits of base64url without padding.
		assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");

		org.mockito.ArgumentCaptor<PortalAccess> saved = org.mockito.ArgumentCaptor.forClass(PortalAccess.class);
		verify(tokens).save(saved.capture());
		PortalAccess row = saved.getValue();

		assertThat(row.matches(PortalAccessService.hash(token))).isTrue();
		assertThat(row.matches(token)).as("the row holds the hash, never the token").isFalse();
		assertThat(row.getBrandId()).as("the brand comes off the case, never a request").isEqualTo(BRAND);
		assertThat(row.getCaseId()).isEqualTo(subject.getId());
		assertThat(row.getAudience()).isEqualTo(PortalAudience.CLIENT);
		assertThat(row.getRevokedAt()).isNull();
		assertThat(row.getLastSeenAt()).as("nobody has opened it yet").isNull();
	}

	/** Expiry is real, and it is absolute rather than a sliding window every visit extends. */
	@Test
	void aLinkExpires() {
		PortalAccess row = minted();

		assertThat(row.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
		assertThat(row.isLive(Instant.now())).isTrue();
		assertThat(row.isLive(Instant.now().plus(Duration.ofDays(31)))).isFalse();
	}

	/**
	 * Re-minting revokes, inside the same transaction.
	 *
	 * <p>Not a nicety: without it every "the link doesn't work" support request would permanently
	 * add another live credential pointing at the same case. The <em>invariant</em> is V23's partial
	 * unique index — this is what keeps the winner of a concurrent mint legal, not what enforces the
	 * rule; see {@code aPortalTokenIsUniqueAndItsAudienceIsClosed} for the constraint itself.
	 */
	@Test
	void reMintingRevokesTheLinkItSupersedes() {
		PortalAccess previous = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null, "old-hash",
				Instant.now().plus(Duration.ofDays(10)));
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(previous));

		links.mint(CASE_ID, PortalAudience.CLIENT);

		assertThat(previous.getRevokedAt()).isNotNull();
		assertThat(previous.isLive(Instant.now())).isFalse();
		verify(tokens).save(previous);
	}

	// --- Unit 35, D1: the party-scoped credential -----------------------------

	/**
	 * A party link lives <strong>7 days</strong>, not 30.
	 *
	 * <p>It opens every case that person has, so it is the wider of the two credentials and must
	 * not outlive the narrow one by four times. Asserted against both bounds rather than just the
	 * lower one: a party TTL that silently picked up {@code link-ttl} would still be "after 6 days"
	 * and would be exactly the bug this exists to catch.
	 */
	@Test
	void aPartyLinkLivesSevenDaysAndNotThirty() {
		given(contacts.findById(any())).willReturn(java.util.Optional.of(contactRow()));
		subject.setContactId(java.util.UUID.randomUUID());

		links.mintForParty(CASE_ID, PortalAudience.CLIENT);

		PortalAccess row = savedRow();
		assertThat(row.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
		assertThat(row.getExpiresAt()).isBefore(Instant.now().plus(Duration.ofDays(8)));
		assertThat(row.isPartyScoped()).isTrue();
		assertThat(row.getGhlContactId()).isEqualTo("ghl-contact-1");
		assertThat(row.getCaseId()).isNull();
	}

	/**
	 * Re-minting a party link revokes the previous <em>party</em> link — and leaves a case link
	 * alone.
	 *
	 * <p>The two shapes are separate credentials with separate indexes ({@code V38}), so issuing a
	 * party link must not kill a case link the Case Manager already sent. The finder this asserts
	 * is the brand-scoped, {@code caseId IS NULL} one, which is what keeps the two apart.
	 */
	@Test
	void reMintingAPartyLinkRevokesOnlyThePreviousPartyLink() {
		given(contacts.findById(any())).willReturn(java.util.Optional.of(contactRow()));
		subject.setContactId(java.util.UUID.randomUUID());

		PortalAccess previousParty = PortalAccess.forParty(BRAND, PortalAudience.CLIENT, "ghl-contact-1", null,
				"old-party-hash", Instant.now().plus(Duration.ofDays(3)));
		given(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				eq(BRAND), eq("ghl-contact-1"), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(previousParty));

		links.mintForParty(CASE_ID, PortalAudience.CLIENT);

		assertThat(previousParty.getRevokedAt()).isNotNull();
		// The case-scoped finder is never consulted on this path: a case link already in somebody's
		// inbox keeps working.
		verify(tokens, never()).findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), any());
	}

	/**
	 * A case with no GHL contact cannot have a client party link.
	 *
	 * <p>The scope would name nobody, and {@code V27} settled that email is a fallback *matching*
	 * key and never an identity — so there is nothing to fall back to (invariant 7). Refusing beats
	 * minting a credential whose reach is undefined.
	 */
	@Test
	void aCaseWithNoContactCannotMintAClientPartyLink() {
		subject.setContactId(null);

		assertThatThrownBy(() -> links.mintForParty(CASE_ID, PortalAudience.CLIENT))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining("no GHL contact");
	}

	private static ContactSnapshot contactRow() {
		return new ContactSnapshot(BRAND, "ghl-contact-1");
	}

	/** The row handed to {@code save}, which is where the minted credential's shape is visible. */
	private PortalAccess savedRow() {
		org.mockito.ArgumentCaptor<PortalAccess> saved = org.mockito.ArgumentCaptor.forClass(PortalAccess.class);
		verify(tokens).save(saved.capture());
		return saved.getValue();
	}

	/**
	 * An <strong>already-expired</strong> row is retired too, and that is what V23's index needs.
	 *
	 * <p>Retiring only the live rows would leave an expired one unrevoked, so it would still occupy
	 * {@code (case_id, audience) WHERE revoked_at IS NULL} and the next mint after a natural expiry
	 * would collide with the index — a client who waited out their link would be unable to get a new
	 * one. Nothing about who may read a token changes, because {@code isLive} already refused it.
	 */
	@Test
	void anExpiredLinkIsRetiredSoTheNextMintDoesNotCollideWithTheIndex() {
		PortalAccess expired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null, "stale-hash",
				Instant.now().minus(Duration.ofDays(1)));
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(expired));

		links.mint(CASE_ID, PortalAudience.CLIENT);

		assertThat(expired.getRevokedAt()).as("an expired row must not stay unrevoked").isNotNull();
		verify(tokens).save(expired);
	}

	/** A row already retired is left exactly as it was — first revocation wins. */
	@Test
	void anAlreadyRetiredLinkIsNotRestamped() {
		PortalAccess retired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null, "older-hash",
				Instant.now().plus(Duration.ofDays(10)));
		Instant revokedAt = Instant.now().minus(Duration.ofHours(3));
		retired.revoke(revokedAt);
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(retired));

		links.mint(CASE_ID, PortalAudience.CLIENT);

		assertThat(retired.getRevokedAt()).isEqualTo(revokedAt);
		verify(tokens, never()).save(retired);
	}

	/** Minting is audited, and the row must not carry the credential it issued. */
	@Test
	void mintingIsAuditedWithoutTheToken() {
		PortalAccessService.MintedLink link = links.mint(CASE_ID, PortalAudience.CLIENT);
		String token = link.url().substring(link.url().indexOf('#') + 1);

		org.mockito.ArgumentCaptor<Object> after = org.mockito.ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("CASE"), any(), eq(AuditAction.PORTAL_LINK_ISSUED), eq(PM), any(),
				after.capture());
		assertThat(after.getValue().toString()).doesNotContain(token).contains("portal link issued");
	}

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
	 * The staff panel is told whether a link is live and never what it is.
	 *
	 * <p>Asserted structurally: {@code LinkStatus} has no component that could carry a token or a
	 * hash, so no future edit to the panel can start showing one without changing this record.
	 */
	@Test
	void theStatusReadCannotLeakTheToken() {
		PortalAccess live = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, null, "hash",
				Instant.now().plus(Duration.ofDays(5)));
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(live));

		assertThat(links.status(CASE_ID, PortalAudience.CLIENT).live()).isTrue();

		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of());
		assertThat(links.status(CASE_ID, PortalAudience.CLIENT))
				.isEqualTo(new PortalAccessService.LinkStatus(false, null, null));

		assertThat(PortalAccessService.LinkStatus.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("live", "expiresAt", "lastSeenAt");
	}

	/**
	 * <strong>Two apps, two origins (Unit 34e).</strong> The expert portal is its own deployment,
	 * so its link cannot be built from the client's base — a link to the wrong host reads to its
	 * holder exactly like a revoked token, and the holder here is the participant EvalOS cannot
	 * train.
	 */
	@Test
	void theExpertsLinkPointsAtTheExpertsOwnApp() {
		subject.setExpertId(UUID.randomUUID());

		assertThat(links.mint(CASE_ID, PortalAudience.EXPERT).url())
				.startsWith("https://experts.evalos.test/case#");
		assertThat(links.mint(CASE_ID, PortalAudience.CLIENT).url())
				.startsWith("https://portal.evalos.test/portal/client#");
	}

	/** An expert link with no expert on the case is a credential naming nobody. */
	@Test
	void anExpertLinkIsRefusedWhenNoExpertIsAssigned() {
		assertThatThrownBy(() -> links.mint(CASE_ID, PortalAudience.EXPERT))
				.isInstanceOf(com.ie.evalos.domain.IllegalTransitionException.class);

		verify(tokens, never()).save(any());
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
		ClientAccount account = new ClientAccount(brand, "ana@example.com");
		account.linkGhlContact("ghl-contact-1");
		PortalAccess previous = PortalAccess.forParty(brand, PortalAudience.CLIENT, "ghl-contact-1",
				null, "old-hash", Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brand, "ghl-contact-1", PortalAudience.CLIENT)).willReturn(List.of(previous));

		PortalAccessService.MintedLink link = links.mintForClientAccount(account);

		assertThat(link.url()).startsWith("https://portal.evalos.test/portal/client#");
		assertThat(previous.getRevokedAt()).isNotNull();

		PortalAccess minted = savedAccess();
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
		ClientAccount account = new ClientAccount(brand, "ana@example.com");
		PortalAccess previous = PortalAccess.forAccount(brand, account.getId(), "old-hash",
				Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByClientAccountIdOrderByCreatedAtDesc(account.getId()))
				.willReturn(List.of(previous));

		PortalAccessService.MintedLink link = links.mintForClientAccount(account);

		assertThat(link.url()).startsWith("https://portal.evalos.test/portal/client#");
		assertThat(previous.getRevokedAt()).isNotNull();
		verify(tokens, never()).findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				any(), any(), any());

		PortalAccess minted = savedAccess();
		assertThat(minted.getGhlContactId()).isNull();
		assertThat(minted.getClientAccountId()).isEqualTo(account.getId());
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

	/** The row this mint wrote, which is the only way to see what was actually scoped. */
	private PortalAccess savedAccess() {
		org.mockito.ArgumentCaptor<PortalAccess> saved =
				org.mockito.ArgumentCaptor.forClass(PortalAccess.class);
		verify(tokens, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		return saved.getAllValues().get(saved.getAllValues().size() - 1);
	}
}
