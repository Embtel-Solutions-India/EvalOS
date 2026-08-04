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

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.PortalAccessRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseLifecycleService.CaseSnapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mint, revoke and resolve the link that admits a client — and, from Unit 15, an expert — to one
 * case. The only place a portal token is created or checked.
 *
 * <p><strong>A portal link is a credential, and is treated as one.</strong> The token is 256 bits
 * from {@link SecureRandom}, base64url, returned exactly once at mint time and stored only as a
 * SHA-256 hash: a database read yields no working link. The comparison is
 * {@link MessageDigest#isEqual}, because a short-circuiting comparison on a secret leaks it a byte
 * at a time — the same reasoning {@code WebhookVerifier} applies to an HMAC.
 *
 * <p><strong>Unknown, expired and revoked are one answer.</strong> {@link #resolve} returns empty
 * for all three, so nothing about which it was is learnable from the response.
 */
@Service
public class PortalAccessService {

	private static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** What a mint answers. The URL carries the token, and this is the only time it exists. */
	public record MintedLink(String url, Instant expiresAt) {
	}

	/**
	 * What staff may know about a link without being shown it: whether one is live, when it
	 * expires, and when it was last opened. Never the token, and never a way back to it.
	 */
	public record LinkStatus(boolean live, Instant expiresAt, Instant lastSeenAt) {

		static final LinkStatus NONE = new LinkStatus(false, null, null);
	}

	private final PortalAccessRepository tokens;
	private final CaseLifecycleService cases;
	private final AuditService audit;
	private final Duration ttl;
	private final String baseUrl;

	PortalAccessService(PortalAccessRepository tokens, CaseLifecycleService cases, AuditService audit,
			@Value("${evalos.portal.link-ttl}") Duration ttl,
			@Value("${evalos.portal.base-url}") String baseUrl) {
		this.tokens = tokens;
		this.cases = cases;
		this.audit = audit;
		this.ttl = ttl;
		// A trailing slash is a configuration typo, not a different URL.
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	// --- mint ----------------------------------------------------------------

	/**
	 * Issues a link for this case, revoking whatever live one it had.
	 *
	 * <p><strong>Re-minting revokes the previous token, inside this transaction.</strong> A client
	 * who says "the link doesn't work" gets a new one and the old one stops working immediately —
	 * otherwise every support request permanently widens the number of live credentials pointing
	 * at one case. This is also where "one live token per case per audience" is enforced, because
	 * it cannot be an index: a partial unique predicate would need {@code now()}, which is not
	 * immutable (see V21).
	 *
	 * <p>The case is loaded through the scoped read, so another brand's case — or, for a Case
	 * Manager, one that is not theirs — cannot have a link minted for it. The brand on the token
	 * comes off that case and never from a request.
	 *
	 * <p>Audited, because a credential was issued toward a client. The snapshot records the
	 * audience and the expiry and <strong>never the token</strong>.
	 */
	@Transactional
	public MintedLink mint(UUID caseId, PortalAudience audience) {
		Case subject = cases.load(caseId);
		Instant now = Instant.now();

		revokeLive(subject.getId(), audience, now);

		String token = freshToken();
		PortalAccess minted = tokens.save(new PortalAccess(
				subject.getBrandId(), subject.getId(), audience, hash(token), now.plus(ttl)));

		audit.recordEvent("CASE", subject.getId(), AuditAction.PORTAL_LINK_ISSUED,
				TenantContext.current().memberId(), CaseSnapshot.of(subject),
				CaseSnapshot.of(subject, "%s portal link issued, expires %s".formatted(
						audience.name().toLowerCase(), minted.getExpiresAt())));

		return new MintedLink(urlFor(audience, token), minted.getExpiresAt());
	}

	private void revokeLive(UUID caseId, PortalAudience audience, Instant now) {
		for (PortalAccess existing : tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(caseId, audience)) {
			if (existing.isLive(now)) {
				existing.revoke(now);
				tokens.save(existing);
			}
		}
	}

	/**
	 * Whether this case has a live link, for the staff panel.
	 *
	 * <p>Reads the newest row rather than filtering for the live one, deliberately: an expired
	 * link is worth showing as expired, and "no link has ever been minted" is a different thing to
	 * say than "the link you sent has run out".
	 */
	@Transactional(readOnly = true)
	public LinkStatus status(UUID caseId, PortalAudience audience) {
		Case subject = cases.load(caseId);
		List<PortalAccess> issued = tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(subject.getId(), audience);
		if (issued.isEmpty()) {
			return LinkStatus.NONE;
		}
		PortalAccess newest = issued.get(0);
		return new LinkStatus(newest.isLive(Instant.now()), newest.getExpiresAt(), newest.getLastSeenAt());
	}

	// --- resolve -------------------------------------------------------------

	/**
	 * Turns a presented token into the principal it admits, stamping {@code last_seen_at}.
	 *
	 * <p>Empty for an unknown, expired or revoked token — one answer for three states, so a caller
	 * learns nothing from the refusal. The write is why this is not {@code readOnly}: last-seen is
	 * the field support needs, and it moves on every use, unlike the case's
	 * {@code client_portal_read_at}, which is stamped once.
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

	// --- the token itself ----------------------------------------------------

	/** 256 bits, base64url, no padding — safe in a URL fragment without escaping. */
	private static String freshToken() {
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

	/**
	 * The whole URL, so the caller never assembles one. The token travels in the <strong>fragment
	 * </strong> and not the query string: a fragment is never sent to the server, so it stays out
	 * of access logs, {@code Referer} headers and any redirect chain. The SPA reads it there and
	 * puts it in the {@code X-Portal-Token} header.
	 */
	private String urlFor(PortalAudience audience, String token) {
		String path = audience == PortalAudience.CLIENT ? "/portal/client" : "/portal/expert";
		return baseUrl + path + "#" + token;
	}
}
