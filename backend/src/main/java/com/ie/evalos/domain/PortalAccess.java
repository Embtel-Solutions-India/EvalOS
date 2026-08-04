package com.ie.evalos.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One link-based credential admitting one audience to one case (Unit 14; Unit 15 mints the
 * {@code EXPERT} half).
 *
 * <p><strong>The token is not here.</strong> Only its SHA-256 is stored, so a database read — a
 * backup, a support query, a leaked dump — yields no working link. The token is returned exactly
 * once, at mint time, by {@code PortalAccessService}.
 *
 * <p><strong>The row is the scope.</strong> {@link #caseId} names the one case this token admits,
 * so no portal route takes a case id and there is nothing to enumerate. Everything that
 * identifies the credential is {@code updatable = false}; the three timestamps that record its
 * life — expiry is fixed at mint, revocation happens once, and last-seen moves — are the only
 * mutable state.
 */
@Entity
@Table(name = "portal_access")
public class PortalAccess extends ScopedEntity {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience", nullable = false, updatable = false)
	private PortalAudience audience;

	/** Hex SHA-256 of the token. Never the token. */
	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	protected PortalAccess() {
		// for JPA
	}

	public PortalAccess(UUID brandId, UUID caseId, PortalAudience audience, String tokenHash, Instant expiresAt) {
		super(brandId);
		this.caseId = caseId;
		this.audience = audience;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	/**
	 * Whether a presented token's hash is this row's.
	 *
	 * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}, and here rather than in
	 * the service, so the one secret comparison on this surface has one home and the stored hash
	 * needs no getter. A short-circuiting comparison on a credential leaks it a byte at a time —
	 * the discipline {@code WebhookVerifier} applies to an HMAC.
	 */
	public boolean matches(String candidateHash) {
		return candidateHash != null && MessageDigest.isEqual(
				tokenHash.getBytes(StandardCharsets.UTF_8), candidateHash.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Whether this token still works.
	 *
	 * <p>Unknown, expired and revoked are three different states here and <strong>one</strong>
	 * answer to the caller: {@code PortalTokenFilter} refuses all three identically, so nothing
	 * about which it was is learnable from the response — the discipline
	 * {@code WebhookVerifier} applies to a signature.
	 */
	public boolean isLive(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	/** First revocation wins: the moment a link stopped working does not change. */
	public void revoke(Instant at) {
		if (revokedAt == null) {
			revokedAt = at;
		}
	}

	/** Stamped on every use, which is what support needs — see {@code client_portal_read_at}. */
	public void seen(Instant at) {
		lastSeenAt = at;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public PortalAudience getAudience() {
		return audience;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}
}
