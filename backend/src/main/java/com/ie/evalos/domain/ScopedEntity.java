package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * What every brand-scoped row carries: its id, its owning brand, and when it was
 * created.
 *
 * <p>The persist hook is the last line of defence for brand isolation. A row that
 * reaches the database with no {@code brand_id} cannot be scoped by anything
 * afterwards — no predicate matches it and no guard can rule on it — so it is
 * refused here rather than written and found later. {@code brand_id} is also
 * non-updatable: a row never changes brand.
 *
 * <p>{@link AuditEvent} deliberately does not extend this: its brand is nullable
 * because system events have no brand.
 */
@MappedSuperclass
public abstract class ScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "brand_id", nullable = false, updatable = false)
	private UUID brandId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ScopedEntity() {
		// for JPA
	}

	protected ScopedEntity(UUID brandId) {
		this.brandId = brandId;
	}

	@PrePersist
	void stampCreatedAtAndRequireBrand() {
		if (brandId == null) {
			throw new IllegalStateException(
					getClass().getSimpleName() + " cannot be persisted without a brand_id");
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getBrandId() {
		return brandId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
