package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One stage of a mirrored GHL pipeline.
 *
 * <p><strong>This is what stops a GHL stage id being opaque</strong> ({@code 00c} §2b, decided in
 * {@code 43} §6c): the id is stored verbatim, and it resolves against this row for the name and
 * the position. Same ids on both sides is strictly better than a mapping table — there is no
 * translation to get wrong, and a stage comparison between the two systems is an equality check.
 *
 * <p><strong>{@link #ghlId} is mutable, which is the one surprise in this class.</strong> GHL stage
 * ids are not stable across a delete-and-recreate, and IE has demonstrated it will recreate things.
 * So when a stage arrives whose id is unknown but whose {@code (pipeline, position, name)} matches
 * a row already held, the sweep repoints that row rather than inserting a second — the row keeps
 * its {@code id}, and every foreign key into it survives the recreate. Without that, a recreated
 * pipeline reads as every opportunity in it having drifted ({@code 00d} §6.7).
 */
@Entity
@Table(name = "pipeline_stage")
public class PipelineStage extends ScopedEntity {

	@Column(name = "pipeline_id", nullable = false, updatable = false)
	private UUID pipelineId;

	/** GHL's id. Updatable — see the class note on delete-and-recreate. */
	@Column(name = "ghl_id", nullable = false)
	private String ghlId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	@Column(name = "missing_since")
	private Instant missingSince;

	protected PipelineStage() {
		// for JPA
	}

	public PipelineStage(UUID brandId, UUID pipelineId, String ghlId, String name, int position) {
		super(brandId);
		this.pipelineId = pipelineId;
		this.ghlId = ghlId;
		this.name = name;
		this.position = position;
		this.syncedAt = Instant.now();
	}

	public void syncFromGhl(String ghlId, String name, int position) {
		this.ghlId = ghlId;
		this.name = name;
		this.position = position;
		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	public UUID getPipelineId() {
		return pipelineId;
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

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public Instant getMissingSince() {
		return missingSince;
	}

	public boolean isLive() {
		return missingSince == null;
	}
}
