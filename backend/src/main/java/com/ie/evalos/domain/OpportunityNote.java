package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One note on a GHL opportunity — the one thing in this programme EvalOS genuinely owns.
 *
 * <p><strong>Why EvalOS owns it at all, when GHL owns everything else about an opportunity:</strong>
 * GHL has nowhere to put it. Its only note endpoints are {@code POST /contacts/{contactId}/notes}
 * and {@code PUT /contacts/{contactId}/notes/{id}} — a note hangs off the <em>contact</em>, so a
 * repeat client's two deals would share one stream with no way to separate them.
 *
 * <p><strong>Keyed on the opportunity, not the contact.</strong> It was put to the business that
 * "an opportunity is itself a contact", which is true of every contact today and is exactly the
 * conflation invariant 7 forbids. A repeat client is one contact and two opportunities.
 *
 * <p><strong>Append-only, and the entity has no setters to say so.</strong> A correction is a new
 * note. The database enforces it with a trigger ({@code V41}), on the same reasoning
 * {@code audit_event} does: the application connects as the table owner, and an owner is not
 * subject to {@code REVOKE}.
 *
 * <p><strong>What this costs, named rather than buried:</strong> a note written <em>in GHL</em>
 * never reaches EvalOS. That is acceptable only because the whole point of this programme is
 * that Sales and Marketing do not open GHL. If anyone works a deal there directly, this design
 * is wrong and gets revisited rather than patched.
 */
@Entity
@Table(name = "opportunity_note")
public class OpportunityNote {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** GHL's opportunity id. No foreign key: the opportunity lives in GHL, not here. */
	@Column(name = "ghl_opportunity_id", nullable = false, updatable = false)
	private String ghlOpportunityId;

	@Column(name = "brand_id", nullable = false, updatable = false)
	private UUID brandId;

	/**
	 * Denormalised from the opportunity so {@code Tier.PIPELINE} can scope a note without
	 * joining {@code ghl_opportunity_cache} — which is droppable, and a scope that depends on a
	 * droppable table fails <em>open</em> the moment it is empty.
	 */
	@Column(name = "ghl_pipeline_id", nullable = false, updatable = false)
	private String ghlPipelineId;

	@Column(name = "author_id", nullable = false, updatable = false)
	private UUID authorId;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected OpportunityNote() {
		// for JPA
	}

	public OpportunityNote(String ghlOpportunityId, UUID brandId, String ghlPipelineId, UUID authorId,
			String body) {
		this.ghlOpportunityId = ghlOpportunityId;
		this.brandId = brandId;
		this.ghlPipelineId = ghlPipelineId;
		this.authorId = authorId;
		this.body = body;
	}

	public UUID getId() {
		return id;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public UUID getBrandId() {
		return brandId;
	}

	public String getGhlPipelineId() {
		return ghlPipelineId;
	}

	public UUID getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
