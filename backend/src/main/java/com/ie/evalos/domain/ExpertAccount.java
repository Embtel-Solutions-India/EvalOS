package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An expert's sign-in (Unit 59, D23), bound to one roster row. **There is no email here**: the
 * address lives on {@link Expert} and is only used to find that row, so the account follows the
 * roster rather than keeping a second copy that could drift from it.
 */
@Entity
@Table(name = "expert_account")
public class ExpertAccount extends ScopedEntity {

	@Column(name = "expert_id", nullable = false, updatable = false)
	private UUID expertId;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "last_sign_in_at")
	private Instant lastSignInAt;

	protected ExpertAccount() {
	}

	public ExpertAccount(UUID brandId, UUID expertId) {
		super(brandId);
		this.expertId = expertId;
	}

	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank();
	}

	public UUID getExpertId() {
		return expertId;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public Instant getLastSignInAt() {
		return lastSignInAt;
	}

	public void recordSignIn(Instant at) {
		this.lastSignInAt = at;
	}
}
