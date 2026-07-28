package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One append-only fact about one object: who did what to it, and when.
 *
 * <p>Append-only is enforced three times over, because losing audit history is
 * not recoverable: every column is mapped {@code updatable = false} so Hibernate
 * can never emit an UPDATE, the repository exposes no update or delete method,
 * and a database trigger refuses both.
 *
 * <p>Not a {@link ScopedEntity}: {@code brand_id} is nullable here because a
 * system event — a job, or an inbound webhook before brand resolution — has no
 * brand. Audit is global.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false)
	private UUID id;

	@Column(name = "brand_id", updatable = false)
	private UUID brandId;

	/** The kind of object, e.g. {@code CASE}, {@code EXPERT}, {@code PAYOUT}. */
	@Column(name = "object_type", nullable = false, updatable = false)
	private String objectType;

	@Column(name = "object_id", nullable = false, updatable = false)
	private UUID objectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, updatable = false)
	private AuditAction action;

	/** The staff member who acted, or null when the actor is the system. */
	@Column(name = "actor_id", updatable = false)
	private UUID actorId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_snapshot", updatable = false)
	private String beforeSnapshot;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_snapshot", updatable = false)
	private String afterSnapshot;

	/**
	 * Stamped on persist from the same clock as {@link ScopedEntity}, deliberately:
	 * a timeline that interleaves an object's {@code created_at} with its audit
	 * rows only orders correctly if both come from one clock. The column keeps its
	 * {@code DEFAULT now()} as a backstop for rows inserted by raw SQL.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AuditEvent() {
		// for JPA
	}

	public AuditEvent(UUID brandId, String objectType, UUID objectId, AuditAction action, UUID actorId,
			String beforeSnapshot, String afterSnapshot) {
		this.brandId = brandId;
		this.objectType = objectType;
		this.objectId = objectId;
		this.action = action;
		this.actorId = actorId;
		this.beforeSnapshot = beforeSnapshot;
		this.afterSnapshot = afterSnapshot;
	}

	@PrePersist
	void stampCreatedAt() {
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

	public AuditAction getAction() {
		return action;
	}

	public UUID getActorId() {
		return actorId;
	}

	public String getBeforeSnapshot() {
		return beforeSnapshot;
	}

	public String getAfterSnapshot() {
		return afterSnapshot;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
