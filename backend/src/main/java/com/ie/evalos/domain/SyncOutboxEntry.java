package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.integration.GhlFailure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One durable push to GHL — Unit 45, slice C.
 *
 * <p><strong>It names a row, not a payload</strong> ({@code 00d} §6.3). The sender reads the current
 * {@code opportunity} at send time, "otherwise 'collapse onto the pending one even if a field
 * changed' silently sends the older values". A queue holding a copy of what was true when it was
 * queued delivers stale writes on every retry.
 *
 * <p><strong>{@link Intent} is coarse on purpose</strong>, for the same reason: three edits to one
 * opportunity in a minute must become <em>one</em> pending row, and an intent per field would make
 * each of them distinct and send three.
 *
 * <p>This is {@code 00c} §4d's "durable row with backoff, not a broker" — and the reason invariant 2
 * could say <em>"writes do not retry"</em> until now is that EvalOS had no key scheme. Unit 44d's
 * correlation key is that scheme.
 */
@Entity
@Table(name = "sync_outbox")
public class SyncOutboxEntry extends ScopedEntity {

	/**
	 * What kind of push this is.
	 *
	 * <p>Three values and no more. The temptation is a verb per operation — rename, reprice, move
	 * stage — and it defeats the whole design: two of them queued against one opportunity are two
	 * rows, two sends, and the second one carries whatever the first one already delivered.
	 */
	public enum Intent {

		/** Make GHL agree with the EvalOS row: create it if it has no {@code ghl_id}, else update. */
		UPSERT,

		/** Set the opportunity's status to a closed one. */
		CLOSE,

		/** Remove it in GHL. Unused today; here so the {@code intent} vocabulary is not reopened. */
		DELETE
	}

	@Column(name = "entity_type", nullable = false, updatable = false)
	@Enumerated(EnumType.STRING)
	private SyncEntity entityType;

	@Column(name = "entity_id", nullable = false, updatable = false)
	private UUID entityId;

	@Column(name = "intent", nullable = false, updatable = false)
	@Enumerated(EnumType.STRING)
	private Intent intent;

	@Column(name = "queued_at", nullable = false, updatable = false)
	private Instant queuedAt;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	/** {@code GhlFailure}'s own name — the point of classifying at the door (Unit 45a). */
	@Column(name = "last_failure")
	@Enumerated(EnumType.STRING)
	private GhlFailure lastFailure;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "dead_at")
	private Instant deadAt;

	@Column(name = "dead_reason")
	private String deadReason;

	protected SyncOutboxEntry() {
		// for JPA
	}

	public SyncOutboxEntry(UUID brandId, SyncEntity entityType, UUID entityId, Intent intent) {
		super(brandId);
		this.entityType = entityType;
		this.entityId = entityId;
		this.intent = intent;
		this.queuedAt = Instant.now();
	}

	public void delivered() {
		this.sentAt = Instant.now();
		this.lastAttemptAt = this.sentAt;
		this.attempts++;
	}

	/** Tried and failed, and worth trying again. */
	public void failedRetriably(GhlFailure failure, String message) {
		this.attempts++;
		this.lastAttemptAt = Instant.now();
		this.lastFailure = failure;
		this.lastError = message;
	}

	/**
	 * Tried and failed in a way no retry can fix, or tried too many times.
	 *
	 * <p>The row stays. A write that never reached GHL is exactly the thing somebody needs to find
	 * afterwards, and deleting it would leave an outage with no record of what it cost.
	 */
	public void dead(GhlFailure failure, String reason) {
		this.attempts++;
		this.lastAttemptAt = Instant.now();
		this.lastFailure = failure;
		this.lastError = reason;
		this.deadAt = this.lastAttemptAt;
		this.deadReason = reason;
	}

	public SyncEntity getEntityType() {
		return entityType;
	}

	public UUID getEntityId() {
		return entityId;
	}

	public Intent getIntent() {
		return intent;
	}

	public Instant getQueuedAt() {
		return queuedAt;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getLastAttemptAt() {
		return lastAttemptAt;
	}

	public GhlFailure getLastFailure() {
		return lastFailure;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public Instant getDeadAt() {
		return deadAt;
	}

	public String getDeadReason() {
		return deadReason;
	}

	public boolean isPending() {
		return sentAt == null && deadAt == null;
	}
}
