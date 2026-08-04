package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
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

import static org.assertj.core.api.Assertions.assertThat;
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

	private final PortalAccessService links = new PortalAccessService(
			tokens, lifecycle, audit, Duration.ofDays(30), "https://portal.evalos.test/");

	private Case subject;

	@BeforeEach
	void aCaseWithADraftWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_GENERATION);
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
		PortalAccess previous = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, "old-hash",
				Instant.now().plus(Duration.ofDays(10)));
		given(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(any(), eq(PortalAudience.CLIENT)))
				.willReturn(List.of(previous));

		links.mint(CASE_ID, PortalAudience.CLIENT);

		assertThat(previous.getRevokedAt()).isNotNull();
		assertThat(previous.isLive(Instant.now())).isFalse();
		verify(tokens).save(previous);
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
		PortalAccess expired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, "stale-hash",
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
		PortalAccess retired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, "older-hash",
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
		PortalAccess expired = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT,
				PortalAccessService.hash(token), Instant.now().minus(Duration.ofDays(1)));
		PortalAccess revoked = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT,
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
		PortalAccess live = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT,
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
		PortalAccess live = new PortalAccess(BRAND, CASE_ID, PortalAudience.CLIENT, "hash",
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
}
