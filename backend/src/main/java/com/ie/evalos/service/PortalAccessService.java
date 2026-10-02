package com.ie.evalos.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.PortalAccessRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mint and resolve the portal token a signed-in client or expert holds. The only place a portal
 * token is created or checked; since 2026-09-28 (Unit 59) the only mint is signing in.
 *
 * <p><strong>A portal link is a credential, and is treated as one.</strong> The token is 256 bits
 * from {@link SecureRandom}, base64url, returned exactly once at mint time and stored only as a
 * SHA-256 hash: a database read yields no working link. The comparison is
 * {@link MessageDigest#isEqual}, because a short-circuiting comparison on a secret leaks it a byte
 * at a time — the same reasoning a signature comparison demands.
 *
 * <p><strong>Unknown, expired and revoked are one answer.</strong> {@link #resolve} returns empty
 * for all three, so nothing about which it was is learnable from the response.
 */
@Service
public class PortalAccessService {

	private static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * What a sign-in answers: the bare token, no URL. The portal is one origin the browser is already
	 * on. <strong>Signing in is the only mint left</strong> — staff-minted expert links were removed
	 * on 2026-09-28 (Unit 59, D23), and clients never had one after Unit 42.
	 */
	public record MintedToken(String token, Instant expiresAt) {
	}

	private final PortalAccessRepository tokens;
	private final Duration partyTtl;

	PortalAccessService(PortalAccessRepository tokens,
			@Value("${evalos.portal.party-link-ttl}") Duration partyTtl) {
		this.tokens = tokens;
		this.partyTtl = partyTtl;
	}

	// --- mint ----------------------------------------------------------------

	/**
	 * Mints a party-scoped expert token for an account whose password has just been verified
	 * (Unit 59) — an ordinary party-scoped expert row, so nothing downstream knows an account
	 * exists. This is the only way an expert token is made. Takes the entity, for the reason {@link #mintForClientAccount} does. The
	 * caller audits the sign-in.
	 */
	@Transactional
	public MintedToken mintForExpertAccount(com.ie.evalos.domain.ExpertAccount account) {
		if (account.getId() == null) {
			throw new IllegalTransitionException("this account has not been persisted, so a token would name nobody");
		}
		Instant now = Instant.now();
		String token = freshToken();
		retirePreviousExpertParty(account.getBrandId(), account.getExpertId(), now);
		PortalAccess minted = tokens.save(PortalAccess.forParty(account.getBrandId(), PortalAudience.EXPERT, null,
				account.getExpertId(), hash(token), now.plus(partyTtl)));
		return new MintedToken(token, minted.getExpiresAt());
	}

	/**
	 * Mints a party-scoped client link for an account whose password has just been verified
	 * (Unit 42).
	 *
	 * <p><strong>This is not a new credential.</strong> It is the same party-scoped
	 * {@code PortalAccess} Unit 35 built and the staff mint button issues, so every screen behind
	 * {@code PortalTokenFilter} consumes it without knowing an account exists. Sign-in is a new
	 * <em>way to obtain</em> the existing credential, not a second kind of session — which is why
	 * this unit adds no security filter chain.
	 *
	 * <p><strong>It takes a {@link ClientAccount}, deliberately, not an email or a contact id.</strong>
	 * See {@link #mintForParty}'s note on enumeration: an entity can only be produced by a lookup
	 * the caller has already passed, so no request body can steer this.
	 *
	 * <p><strong>An account with no GHL contact still mints</strong>, scoped to the account id
	 * instead ({@code V44} widened the scope constraint to admit that row). That is the minority
	 * shape — {@code V45} seeds the contact id from {@code contact_snapshot}, so the ordinary
	 * signed-in client takes the branch above — but it is the one where "EvalOS works when GHL is
	 * removed" stops being a slogan: a self-signup who is not in GHL yet signs in and reaches
	 * their documents with no GHL row anywhere. The principal that comes back from
	 * {@link #resolve} then carries a null contact id, which the portal reads already fail closed
	 * on ({@code PortalCaseService}) or answer empty for ({@code PortalInvoiceService}).
	 *
	 * <p><strong>The account's previous token is retired whichever shape this mint takes</strong>,
	 * and that is not symmetry for its own sake. {@code linkGhlContact} is a normal transition —
	 * Unit 43 pushes a signed-up client to GHL — so a client can sign in contactless on Monday and
	 * with a contact on Tuesday. Retiring only within the shape being minted would leave Monday's
	 * account-scoped credential live for the rest of its seven days, and "re-minting revokes the
	 * previous" would quietly stop being true for exactly the client the shape change describes.
	 * The contact-scoped row is retired by the contact branch, which is the only shape that can
	 * collide with V38's index.
	 *
	 * <p><strong>A transient account is refused</strong>, rather than minting a row scoped to
	 * nothing. Its id is assigned at persist ({@code ScopedEntity}), so an unsaved entity
	 * would mint a row naming nobody — which {@code V44}'s widened constraint rejects at commit
	 * anyway, but as a 500 rather than as a sentence saying what went wrong. It also makes the
	 * retirement above safe: a null id there would match no row and silently retire nothing.
	 *
	 * <p>Not audited here. The caller audits {@code CLIENT_SIGNED_IN} with the account as the
	 * subject, because a credential issued <em>as part of</em> a sign-in is one event, not two.
	 */
	@Transactional
	public MintedToken mintForClientAccount(ClientAccount account) {
		if (account.getId() == null) {
			throw new IllegalTransitionException(
					"this account has not been persisted, so a token would name nobody");
		}
		Instant now = Instant.now();
		String token = freshToken();
		String contact = account.getGhlContactId();
		PortalAccess minted;

		retire(tokens.findByClientAccountIdOrderByCreatedAtDesc(account.getId()), now);

		if (contact != null && !contact.isBlank()) {
			retirePreviousClientParty(account.getBrandId(), contact, now);
			minted = tokens.save(PortalAccess.forParty(account.getBrandId(), PortalAudience.CLIENT,
					contact, null, hash(token), now.plus(partyTtl)));
		}
		else {
			minted = tokens.save(PortalAccess.forAccount(account.getBrandId(), account.getId(),
					hash(token), now.plus(partyTtl)));
		}

		return new MintedToken(token, minted.getExpiresAt());
	}

	/**
	 * The party equivalents, one per audience — {@code V38} indexes each shape separately, so each
	 * has its own row to supersede. Both are brand-scoped: the same contact may be a client of two
	 * brands and the same expert may sit on two panels, and minting one brand's link must not
	 * revoke the other's.
	 */
	private void retirePreviousClientParty(UUID brandId, String ghlContactId, Instant now) {
		retire(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brandId, ghlContactId, PortalAudience.CLIENT), now);
	}

	private void retirePreviousExpertParty(UUID brandId, UUID expertId, Instant now) {
		retire(tokens.findByBrandIdAndExpertIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brandId, expertId, PortalAudience.EXPERT), now);
	}

	/**
	 * <strong>{@code saveAndFlush}, and the flush is load-bearing.</strong> Hibernate's ActionQueue
	 * runs insertions before updates at flush, so a plain {@code save} here leaves the revocation
	 * queued <em>behind</em> the insert the mint is about to make — and the partial unique indexes
	 * this method exists to satisfy are checked by the database when that insert lands, at which
	 * point the previous row is still unrevoked. The second mint then fails with a duplicate key on
	 * the very row it had just superseded.
	 *
	 * <p>Not a hypothesis: {@code LocalPostgresIntegrationTest
	 * .anAccountScopedPortalTokenIsLegalAndStillOnlyOneLives} reproduced it on
	 * {@code portal_access_one_live_per_account} the first time a client signed in twice. The shape
	 * is the same for {@code V23}'s and {@code V38}'s indexes, which is why the flush lives here,
	 * in the one method every mint retires through, rather than at the one call site that caught it.
	 */
	private void retire(List<PortalAccess> superseded, Instant now) {
		for (PortalAccess existing : superseded) {
			if (existing.getRevokedAt() == null) {
				existing.revoke(now);
				tokens.saveAndFlush(existing);
			}
		}
	}

	// --- resolve -------------------------------------------------------------

	/**
	 * Turns a presented token into the principal it admits, stamping {@code last_seen_at}.
	 *
	 * <p>Empty for an unknown, expired or revoked token — one answer for three states, so a caller
	 * learns nothing from the refusal. The write is why this is not {@code readOnly}: last-seen is
	 * the field support needs, and it moves on every use, unlike the case's
	 * {@code client_portal_read_at}, which is stamped once.
	 *
	 * <p><strong>Unit 42's account-scoped row needs no branch here, and that is the point.</strong>
	 * This method never asks what shape the row is — {@link PortalPrincipal#of} copies the columns
	 * and {@code caseId == null} is what makes it party-scoped — so a token minted by
	 * {@link #mintForClientAccount} resolves through exactly the path a staff-minted party token
	 * does. What an account-scoped principal carries is a <em>null</em> contact id, which is the
	 * whole downstream contract: {@code PortalCaseService} fails closed on it and the two
	 * GHL-backed reads answer empty. Do not add a shape check here to "fix" that null.
	 */
	@Transactional
	public Optional<PortalPrincipal> resolve(String presented) {
		if (presented == null || presented.isBlank()) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		String presentedHash = hash(presented);
		return tokens.findByTokenHash(presentedHash)
				// The unique index found the row; {@link PortalAccess#matches} is what accepts it,
				// in constant time. Two steps rather than one because the finder is an index lookup
				// and this is a secret comparison — if the lookup is ever widened (a prefix index, a
				// case-insensitive column), the check that matters is still exact and still timing-safe.
				.filter(access -> access.matches(presentedHash))
				.filter(access -> access.isLive(now))
				.map(access -> {
					access.seen(now);
					return PortalPrincipal.of(tokens.save(access));
				});
	}

	/**
	 * Ends a session now rather than in seven days (Unit 75, D72): the presented token's row is
	 * revoked, so a copy of it left in another tab or a log stops working. Unknown, expired or
	 * already-revoked tokens are a no-op — signing out twice is not an error.
	 */
	@Transactional
	public void revoke(String presented) {
		if (presented == null || presented.isBlank()) {
			return;
		}
		String presentedHash = hash(presented);
		tokens.findByTokenHash(presentedHash)
				.filter(access -> access.matches(presentedHash))
				.filter(access -> access.getRevokedAt() == null)
				.ifPresent(access -> {
					access.revoke(Instant.now());
					tokens.save(access);
				});
	}

	// --- the token itself ----------------------------------------------------

	/** 256 bits, base64url, no padding — safe in a URL fragment without escaping. */
	private static String freshToken() {
		return freshCredentialToken();
	}

	/**
	 * The same generator, promoted for {@code ClientCredentialToken} (Unit 42): a set/reset-password
	 * link is a credential exactly like a portal link, and there is no reason for the two kinds to
	 * draw randomness differently. One generator, one place, so they cannot drift in entropy.
	 */
	static String freshCredentialToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the platform; unreachable.
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
