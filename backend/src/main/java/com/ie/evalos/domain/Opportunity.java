package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One GHL opportunity, held as an EvalOS row — Unit 44, slice D.
 *
 * <p><strong>This replaces {@link CachedOpportunity}, and the difference is not the columns.</strong>
 * That class is refilled by delete-all-then-insert-all per pipeline, so every row's identity is
 * destroyed on every board refresh — {@code 00d} §6.5's fifth and deepest reason it cannot be
 * salvaged: nothing can hold a foreign key into it, and any row carrying local state dies. The
 * write model there is "truth lives elsewhere, replace everything"; here it is the opposite.
 *
 * <p><strong>Two names, and this is the table that earns them</strong> ({@code 00c} §2a). A
 * portal-born opportunity exists <em>before GHL has seen it</em>: the client picks a service, EvalOS
 * opens the row, and GHL is called. With GHL's id as the primary key that row cannot exist, and
 * when GHL later assigns one the row's identity would change, taking every foreign key with it. So
 * {@link #getId()} is stable from creation and {@link #getGhlId()} is null until GHL answers.
 *
 * <p><strong>{@code id} is also the correlation key</strong> ({@code 00d} §6.1). It is written into
 * a GHL custom field on create so a retry after a timeout can ask "did my create land?" rather than
 * guess. At-least-once delivery over a non-idempotent create is exactly how one opportunity becomes
 * two, and no amount of outbox discipline fixes it without a key GHL will hand back.
 *
 * <p><strong>Per-field ownership, not "EvalOS wins"</strong> ({@code 00d} §6.2). {@code assignedTo}
 * is GHL's — a round-robin automation reassigning a deal is not a conflict to be undone, and
 * reverting it would break the one thing {@code 00b} keeps GHL for. The shared fields are name,
 * amount, stage and status. Unit 45 is where that policy becomes code; this class is where the
 * columns it needs live.
 */
@Entity
@Table(name = "opportunity")
public class Opportunity extends ScopedEntity {

	/** GHL's id, or null while this row exists only here. */
	@Column(name = "ghl_id")
	private String ghlId;

	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "pipeline_id", nullable = false)
	private UUID pipelineId;

	/**
	 * GHL's stage id, resolved against {@link PipelineStage#getGhlId()} rather than by a foreign
	 * key — see the migration's note. The two mirrors run on two schedules, and a sync that failed
	 * because a different sweep was behind would turn one late job into two broken screens.
	 */
	@Column(name = "ghl_stage_id")
	private String ghlStageId;

	@Column(name = "name")
	private String name;

	@Column(name = "amount")
	private BigDecimal amount;

	/** GHL's own word — open / won / lost / abandoned. Never an EvalOS vocabulary. */
	@Column(name = "status")
	private String status;

	@Column(name = "source")
	private String source;

	@Column(name = "ghl_assigned_to")
	private String ghlAssignedTo;

	@Column(name = "ghl_created_at")
	private Instant ghlCreatedAt;

	/**
	 * GHL's last-modified.
	 *
	 * <p><strong>Null is a conflict, never "EvalOS is newer"</strong> ({@code 00d} §6.2). It is
	 * GHL-supplied and nullable, so a policy that treated absence as staleness would degrade
	 * silently into "EvalOS always wins" — which is the blanket rule that section exists to reject.
	 */
	@Column(name = "ghl_updated_at")
	private Instant ghlUpdatedAt;

	@Column(name = "last_status_change_at")
	private Instant lastStatusChangeAt;

	@Column(name = "last_stage_change_at")
	private Instant lastStageChangeAt;

	@Column(name = "local_updated_at")
	private Instant localUpdatedAt;

	@Column(name = "synced_at")
	private Instant syncedAt;

	@Column(name = "missing_since")
	private Instant missingSince;

	protected Opportunity() {
		// for JPA
	}

	/** A row GHL already has. */
	public Opportunity(UUID brandId, String ghlId, UUID pipelineId) {
		super(brandId);
		this.ghlId = ghlId;
		this.pipelineId = pipelineId;
	}

	/**
	 * Everything GHL says about this deal, in one call.
	 *
	 * <p>Deliberately one method rather than a setter per column: a partial update from a sync is a
	 * row that half-agrees with GHL, and the whole value of a mirror is that a comparison means
	 * something.
	 */
	public void syncFromGhl(String ghlContactId, UUID pipelineId, String ghlStageId, String name,
			BigDecimal amount, String status, String source, String ghlAssignedTo, Instant ghlCreatedAt,
			Instant ghlUpdatedAt, Instant lastStatusChangeAt, Instant lastStageChangeAt) {
		this.ghlContactId = ghlContactId;
		this.pipelineId = pipelineId;
		this.ghlStageId = ghlStageId;
		this.name = name;
		this.amount = amount;
		this.status = status;
		this.source = source;
		this.ghlAssignedTo = ghlAssignedTo;
		this.ghlCreatedAt = ghlCreatedAt;
		this.ghlUpdatedAt = ghlUpdatedAt;
		this.lastStatusChangeAt = lastStatusChangeAt;
		this.lastStageChangeAt = lastStageChangeAt;
		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	/** GHL answered a create. The row keeps its id and gains GHL's. */
	public void linkGhl(String ghlId) {
		if (this.ghlId == null) {
			this.ghlId = ghlId;
			this.syncedAt = Instant.now();
		}
	}

	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	/** Stamped whenever EvalOS changes the row itself, for Unit 45's comparison. */
	public void touchedLocally() {
		this.localUpdatedAt = Instant.now();
	}

	public String getGhlId() {
		return ghlId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public UUID getPipelineId() {
		return pipelineId;
	}

	public String getGhlStageId() {
		return ghlStageId;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getStatus() {
		return status;
	}

	public String getSource() {
		return source;
	}

	public String getGhlAssignedTo() {
		return ghlAssignedTo;
	}

	public Instant getGhlCreatedAt() {
		return ghlCreatedAt;
	}

	public Instant getGhlUpdatedAt() {
		return ghlUpdatedAt;
	}

	public Instant getLastStatusChangeAt() {
		return lastStatusChangeAt;
	}

	public Instant getLastStageChangeAt() {
		return lastStageChangeAt;
	}

	public Instant getLocalUpdatedAt() {
		return localUpdatedAt;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public Instant getMissingSince() {
		return missingSince;
	}

	public boolean isLive() {
		return missingSince == null;
	}

	/** True while this row exists only in EvalOS — GHL has not acknowledged it yet. */
	public boolean isLocalOnly() {
		return ghlId == null;
	}
}
