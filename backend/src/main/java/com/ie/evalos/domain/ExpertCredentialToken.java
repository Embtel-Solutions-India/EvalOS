package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** A single-use set / reset link for an expert account (Unit 59) — {@link ClientCredentialToken}'s twin. */
@Entity
@Table(name = "expert_credential_token")
public class ExpertCredentialToken extends ScopedEntity {

	@Column(name = "expert_account_id", nullable = false, updatable = false)
	private UUID expertAccountId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false)
	private CredentialPurpose purpose;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected ExpertCredentialToken() {
	}

	public ExpertCredentialToken(UUID brandId, UUID expertAccountId, String tokenHash, CredentialPurpose purpose,
			Instant expiresAt) {
		super(brandId);
		this.expertAccountId = expertAccountId;
		this.tokenHash = tokenHash;
		this.purpose = purpose;
		this.expiresAt = expiresAt;
	}

	public boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	public void markUsed(Instant at) {
		this.usedAt = at;
	}

	public UUID getExpertAccountId() {
		return expertAccountId;
	}

	public CredentialPurpose getPurpose() {
		return purpose;
	}
}
