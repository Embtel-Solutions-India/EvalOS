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
 * <p><strong>No longer append-only</strong> (Unit 54a, {@code V67}, 2026-09-24): the business chose to
 * let a note's author overwrite or hard-delete it, and {@code V41}'s trigger is gone. What survives
 * an edit or a delete is an {@code audit_event} saying who and when — never the words.
 *
 * <p><strong>Revisited, as this comment said it would be</strong> (Unit 54, 2026-09-24): people do
 * work deals in GHL, so notes now travel both ways. This row is pushed once to the deal's contact
 * and its GHL id is written beside it in {@code opportunity_note_ghl_link}; an edit or delete is
 * queued to GHL the same way (54a). GHL's own notes are read back from {@code ghl_note} and shown
 * with these, still stored apart.
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

	/** Editable by its author since Unit 54a — overwritten in place, the previous text not kept. */
	@Column(name = "body", nullable = false)
	private String body;

	/** Null until the first edit. */
	@Column(name = "updated_at")
	private Instant updatedAt;

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

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** Replaces the text (Unit 54a). Who may call this is the service's rule: the author only. */
	public void edit(String newBody) {
		this.body = newBody;
		this.updatedAt = Instant.now();
	}
}
