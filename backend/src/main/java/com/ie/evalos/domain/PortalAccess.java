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
 * <p><strong>The row is the scope, and since Unit 35 the scope has two shapes.</strong> A
 * <em>case-scoped</em> row names one case in {@link #caseId} — the original credential, still
 * legal, still 30 days, still the right thing for a link forwarded once and revocable on its own.
 * A <em>party-scoped</em> row leaves {@code caseId} null and names a person instead:
 * {@link #ghlContactId} for a client, {@link #expertId} for an expert. It answers "my cases",
 * which is what the delivered list screens draw and what no case token can say. Unit 42 adds a
 * third name for a client party — {@link #clientAccountId}, for the client who signs in and has no
 * GHL contact behind them at all.
 *
 * <p>A party token is the wider credential, so it lives 7 days against the case token's 30 — see
 * {@code evalos.portal.party-link-ttl}. Everything else is identical: 256 bits from
 * {@code SecureRandom}, returned once, stored as a hash, one live token per scope, re-mint revokes
 * the previous, and unknown/expired/revoked answer one 401.
 *
 * <p>Everything that identifies the credential is {@code updatable = false}; the three timestamps
 * that record its life — expiry is fixed at mint, revocation happens once, and last-seen moves —
 * are the only mutable state.
 */
@Entity
@Table(name = "portal_access")
public class PortalAccess extends ScopedEntity {

	/** The one case a case-scoped token admits, and <strong>null on a party-scoped row.</strong> */
	@Column(name = "case_id", updatable = false)
	private UUID caseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience", nullable = false, updatable = false)
	private PortalAudience audience;

	/** Hex SHA-256 of the token. Never the token. */
	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	/**
	 * The expert this token was minted for (V37), and null on a {@code CLIENT} row.
	 *
	 * <p><strong>Why a credential names a person as well as a case.</strong> Without it, one
	 * expert's token is indistinguishable from another's on the same case — so a token that
	 * outlived a rematch admitted the wrong expert to a case that had moved on, up to and
	 * including uploading the deliverable in their own name. The read fails closed on a null here,
	 * so a token minted before the column stops working rather than being waved through.
	 */
	@Column(name = "expert_id", updatable = false)
	private UUID expertId;

	/**
	 * The client party this token admits, and null on every case-scoped row and every expert row.
	 *
	 * <p><strong>GHL's contact id, never an email</strong> (invariant 7; {@code V27} settled that
	 * email is a fallback matching key only). It is the same identifier the S3 key prefix uses, so
	 * one client resolves in GHL, in the bucket and in EvalOS with no mapping table.
	 */
	@Column(name = "ghl_contact_id", updatable = false)
	private String ghlContactId;

	/**
	 * The EvalOS account this token admits (Unit 42), and null on every other shape.
	 *
	 * <p><strong>The second legal name for a client party</strong>, added by {@code V44} because
	 * V38's constraint required a {@code CLIENT} party row to carry a GHL contact id — which is a
	 * row most clients cannot produce since IE's GHL sub-account was replaced on 2026-09-11. A
	 * client who has signed in but has no GHL contact is scoped to this instead, and the widened
	 * constraint still refuses a row scoped to nothing.
	 */
	@Column(name = "client_account_id", updatable = false)
	private UUID clientAccountId;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	protected PortalAccess() {
		// for JPA
	}

	/**
	 * A <strong>case-scoped</strong> credential: the original shape, unchanged.
	 *
	 * @param expertId the expert this admits, required for {@code EXPERT} and null for
	 *                 {@code CLIENT} — on a case-scoped client token the client is identified by
	 *                 the case's own contact, and a second copy of that here would be a second
	 *                 thing to keep in step
	 */
	public PortalAccess(UUID brandId, UUID caseId, PortalAudience audience, UUID expertId, String tokenHash,
			Instant expiresAt) {
		super(brandId);
		this.caseId = caseId;
		this.audience = audience;
		this.expertId = expertId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	/**
	 * A <strong>party-scoped</strong> credential (Unit 35, D1): every case this person has.
	 *
	 * <p>Two factories rather than one constructor with a nullable case, because the two shapes are
	 * different credentials with different lifetimes and different blast radii, and a single
	 * constructor taking four nullable ids is one transposed argument away from minting the wrong
	 * one. The database backs this up — {@code portal_access_scope_is_one_thing} refuses a row
	 * scoped to nothing.
	 */
	public static PortalAccess forParty(UUID brandId, PortalAudience audience, String ghlContactId, UUID expertId,
			String tokenHash, Instant expiresAt) {
		PortalAccess party = new PortalAccess(brandId, null, audience, expertId, tokenHash, expiresAt);
		party.ghlContactId = ghlContactId;
		return party;
	}

	/**
	 * An <strong>account-scoped</strong> credential (Unit 42): every case this EvalOS account has,
	 * for a client with no GHL contact behind them.
	 *
	 * <p>A third factory rather than a nullable argument on {@link #forParty}, for the reason
	 * written above it: the shapes are different credentials, and one constructor taking four
	 * nullable ids is one transposed argument away from minting the wrong one. The audience is not
	 * a parameter because there is only one answer — an expert has no client account.
	 */
	public static PortalAccess forAccount(UUID brandId, UUID clientAccountId, String tokenHash,
			Instant expiresAt) {
		PortalAccess account = new PortalAccess(brandId, null, PortalAudience.CLIENT, null, tokenHash, expiresAt);
		account.clientAccountId = clientAccountId;
		return account;
	}

	/**
	 * Whether this credential names a person rather than a case.
	 *
	 * <p>Asked of {@code caseId} and not of the party columns, because {@code caseId} is the one
	 * that decides which read runs. An expert row carries {@code expertId} in <em>both</em> shapes
	 * — as an identity check on a case token ({@code V37}) and as the scope on a party token — so
	 * testing that column would answer the wrong question.
	 */
	public boolean isPartyScoped() {
		return caseId == null;
	}

	/**
	 * Whether a presented token's hash is this row's.
	 *
	 * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}, and here rather than in
	 * the service, so the one secret comparison on this surface has one home and the stored hash
	 * needs no getter. A short-circuiting comparison on a credential leaks it a byte at a time —
	 * the discipline a signature comparison demands.
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
	 * a signature comparison demands.
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

	public UUID getExpertId() {
		return expertId;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public UUID getClientAccountId() {
		return clientAccountId;
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
