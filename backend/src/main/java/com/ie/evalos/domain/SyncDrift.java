package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One thing EvalOS and GHL disagree about — Unit 45, slice B.
 *
 * <p><strong>A row, not a log line.</strong> {@code 00d} §6.3: {@code 00c} §4c's guarantee that
 * <em>every divergence is detected</em> "is only checkable if yesterday's divergences are still
 * queryable". A report printed somewhere is a claim; this is evidence.
 *
 * <p><strong>Detected here, never repaired here.</strong> Resolution is per-field ownership
 * ({@code 00d} §6.2) and belongs to a later slice. A detector that also mutates cannot be trusted to
 * tell the truth, because its own writes become the next night's findings.
 *
 * <p><strong>One open row per thing that is wrong</strong>, with {@code firstDetectedAt} and
 * {@code lastSeenAt} rather than a row per audit run — a drift that persists for a week is one fact,
 * and a row per night buries the new findings under the old ones. Resolved rows stay: "this drifted
 * and then stopped" is the history worth keeping, and deleting it would make the table unable to
 * say whether a fix worked.
 */
@Entity
@Table(name = "sync_drift")
public class SyncDrift extends ScopedEntity {

	/** What kind of disagreement this is. */
	public enum Kind {

		/** GHL has a row EvalOS has never seen. The mirror is behind, or a sync was lost. */
		MISSING_LOCALLY,

		/**
		 * EvalOS holds a row with a GHL id that GHL no longer returns.
		 *
		 * <p>Not the same as a portal-born row with no {@code ghl_id} — that is a legal state, not
		 * drift, and the sweep excludes it.
		 */
		MISSING_IN_GHL,

		/** Both sides have the row and one field disagrees. */
		FIELD_MISMATCH
	}

	/**
	 * What the engine will do about this row — Unit 45e.
	 *
	 * <p><strong>Derived at read time, never a column.</strong> It is a function of the field's
	 * {@link FieldOwnership} and of whether EvalOS still holds an unconfirmed edit, both of which
	 * change after the row was written — a stored value would be yesterday's answer presented as
	 * today's, which is the failure the drift table exists to avoid.
	 *
	 * <p><strong>This is the surface's "resolution half", and it is not a button.</strong>
	 * {@code SyncStatusController} says why there is no route to clear a row: a human clearing one
	 * clears the symptom while the two systems still disagree. What a GM is owed instead is the
	 * answer to "will this fix itself" — which is exactly these three values.
	 */
	public enum Resolution {

		/** The mirror takes GHL's value at the next sync and the row closes itself. No action. */
		GHL_WINS,

		/**
		 * EvalOS holds an edit GHL has not confirmed, so the mirror keeps the local value on
		 * purpose ({@code 45-sync-engine.md} §3.2). Expected, not broken — and it stops being
		 * expected if it persists, because the edit should have reached GHL.
		 */
		EVALOS_WINS,

		/**
		 * Nobody can fix it automatically. A row GHL no longer returns is the case that matters:
		 * re-creating or deleting a deal is not a sweep's decision.
		 */
		NEEDS_A_HUMAN
	}

	@Enumerated(EnumType.STRING)
	@Column(name = "entity_type", nullable = false, updatable = false)
	private SyncEntity entityType;

	@Column(name = "entity_id", updatable = false)
	private UUID entityId;

	@Column(name = "ghl_id", updatable = false)
	private String ghlId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false)
	private Kind kind;

	@Column(name = "field", updatable = false)
	private String field;

	@Column(name = "local_value")
	private String localValue;

	@Column(name = "ghl_value")
	private String ghlValue;

	@Column(name = "first_detected_at", nullable = false, updatable = false)
	private Instant firstDetectedAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	protected SyncDrift() {
		// for JPA
	}

	public SyncDrift(UUID brandId, SyncEntity entityType, UUID entityId, String ghlId, Kind kind,
			String field, String localValue, String ghlValue) {
		super(brandId);
		this.entityType = entityType;
		this.entityId = entityId;
		this.ghlId = ghlId;
		this.kind = kind;
		this.field = field;
		this.localValue = localValue;
		this.ghlValue = ghlValue;
		this.firstDetectedAt = Instant.now();
		this.lastSeenAt = this.firstDetectedAt;
	}

	/**
	 * Tonight's audit found this still wrong.
	 *
	 * <p>The values are refreshed as well as the timestamp: a mismatch whose two sides have both
	 * moved on is still the same disagreement, and showing last week's numbers beside today's date
	 * would be worse than showing neither.
	 */
	public void seenAgain(String localValue, String ghlValue) {
		this.localValue = localValue;
		this.ghlValue = ghlValue;
		this.lastSeenAt = Instant.now();
		this.resolvedAt = null;
	}

	/** The two sides agree again. The row stays. */
	public void resolve() {
		if (this.resolvedAt == null) {
			this.resolvedAt = Instant.now();
		}
	}

	public SyncEntity getEntityType() {
		return entityType;
	}

	public UUID getEntityId() {
		return entityId;
	}

	public String getGhlId() {
		return ghlId;
	}

	public Kind getKind() {
		return kind;
	}

	public String getField() {
		return field;
	}

	public String getLocalValue() {
		return localValue;
	}

	public String getGhlValue() {
		return ghlValue;
	}

	public Instant getFirstDetectedAt() {
		return firstDetectedAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public boolean isOpen() {
		return resolvedAt == null;
	}
}
