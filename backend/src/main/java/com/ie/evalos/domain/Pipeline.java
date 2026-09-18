package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One GHL pipeline, held as an EvalOS row — the first brick of the mirror ({@code 00c} §2).
 *
 * <p><strong>GHL owns every field here except {@link #purpose}</strong> ({@code 00d} §6.2:
 * "`pipeline.*`: GHL, read-only; GHL always wins; EvalOS never pushes"). That makes this the one
 * mirrored table that can never be in conflict — only behind — which is why Unit 44 starts here.
 *
 * <p><strong>Not a cache, and the write path is the difference.</strong>
 * {@link CachedOpportunity} is refilled by delete-all-then-insert-all, so nothing can hold a
 * foreign key into it and no row can carry local state. This is upserted in place and never
 * deleted: a pipeline GHL stops returning is stamped {@link #missingSince} and kept, because
 * {@code purpose} is EvalOS's own judgement and a pipeline archived for an afternoon must not come
 * back having lost it.
 */
@Entity
@Table(name = "pipeline")
public class Pipeline extends ScopedEntity {

	/** GHL's id, verbatim — the join key for every comparison against GHL ({@code 00c} §2). */
	@Column(name = "ghl_id", nullable = false, updatable = false)
	private String ghlId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "position", nullable = false)
	private int position;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false)
	private PipelinePurpose purpose = PipelinePurpose.UNASSIGNED;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	/** Non-null once GHL stopped returning this pipeline. A soft delete, never a hard one. */
	@Column(name = "missing_since")
	private Instant missingSince;

	/**
	 * When this pipeline's <em>opportunities</em> were last read from GHL — <strong>including a read
	 * that returned none</strong>.
	 *
	 * <p><strong>Distinct from {@link #syncedAt}</strong>, which is when the pipeline itself was last
	 * confirmed by the {@code PIPELINE_MIRROR} sweep. Two different sweeps answer two different
	 * questions and conflating them would make a stale board look fresh.
	 *
	 * <p><strong>This column exists because the answer used to be derived, and the derivation was
	 * wrong.</strong> The board asked {@code max(opportunity.synced_at)} over the pipeline's deals,
	 * so a pipeline with no deals reported the same thing as one never synced — and after Unit 46
	 * turned a null into a "Sync delayed" banner, every empty pipeline accused the sync of being
	 * broken. A sync is something that happens to a pipeline; how many rows came back is a separate
	 * fact.
	 */
	@Column(name = "opportunities_synced_at")
	private Instant opportunitiesSyncedAt;

	protected Pipeline() {
		// for JPA
	}

	public Pipeline(UUID brandId, String ghlId, String name, int position) {
		super(brandId);
		this.ghlId = ghlId;
		this.name = name;
		this.position = position;
		this.syncedAt = Instant.now();
	}

	/**
	 * What GHL said this time.
	 *
	 * <p><strong>{@code purpose} is deliberately not touched.</strong> GHL has no such concept, so
	 * a sweep that wrote it would be overwriting EvalOS's own judgement with a default on every
	 * pass. Clearing {@code missingSince} is the other half: a pipeline that came back is present
	 * again, and it keeps the meaning it had before it went.
	 */
	public void syncFromGhl(String name, int position) {
		this.name = name;
		this.position = position;
		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	/** GHL did not return this pipeline. Stamped once and left alone on later sweeps. */
	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	/** A GM says what this pipeline is for. The only field EvalOS writes. */
	public void setPurpose(PipelinePurpose purpose) {
		this.purpose = purpose;
	}

	public String getGhlId() {
		return ghlId;
	}

	public String getName() {
		return name;
	}

	public int getPosition() {
		return position;
	}

	public PipelinePurpose getPurpose() {
		return purpose;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public Instant getMissingSince() {
		return missingSince;
	}

	/** Stamped by every opportunity read for this pipeline, empty answers included. */
	public void opportunitiesSynced() {
		this.opportunitiesSyncedAt = Instant.now();
	}

	public Instant getOpportunitiesSyncedAt() {
		return opportunitiesSyncedAt;
	}

	public boolean isLive() {
		return missingSince == null;
	}
}
