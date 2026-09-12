package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A single-use link that lets somebody set a password (Unit 42).
 *
 * <p><strong>Only the SHA-256 is stored</strong>, exactly as {@code PortalAccess} stores its own:
 * a database read yields no working link. The token itself exists once, in the email.
 *
 * <p><strong>Single use is enforced by {@code usedAt}, not by deletion.</strong> A deleted row
 * cannot tell a second visitor that their link was already spent, and the difference between
 * "already used" and "never existed" is worth keeping for support.
 */
@Entity
@Table(name = "client_credential_token")
public class ClientCredentialToken extends ScopedEntity {

	@Column(name = "client_account_id", nullable = false, updatable = false)
	private UUID clientAccountId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false)
	private CredentialPurpose purpose;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected ClientCredentialToken() {
		// for JPA
	}

	public ClientCredentialToken(UUID brandId, UUID clientAccountId, String tokenHash,
			CredentialPurpose purpose, Instant expiresAt) {
		super(brandId);
		this.clientAccountId = clientAccountId;
		this.tokenHash = tokenHash;
		this.purpose = purpose;
		this.expiresAt = expiresAt;
	}

	/** Unused and unexpired. Both halves, because either alone lets a spent link work again. */
	public boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	public void markUsed(Instant at) {
		this.usedAt = at;
	}

	public UUID getClientAccountId() {
		return clientAccountId;
	}

	public CredentialPurpose getPurpose() {
		return purpose;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}
}
