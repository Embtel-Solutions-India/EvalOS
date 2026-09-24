package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The GHL note an {@link OpportunityNote} became when it was pushed (Unit 54, {@code V66}).
 *
 * <p><strong>A second fact beside the note.</strong> Written once, when the push lands. It hides the
 * pushed note's echo from the timeline, and since Unit 54a ({@code V67}) it <em>outlives</em> a
 * deleted note until the drain has told GHL — which is why it carries the contact and has no
 * foreign key to the note. The drain deletes it once GHL confirms.
 */
@Entity
@Table(name = "opportunity_note_ghl_link")
public class OpportunityNoteGhlLink {

	@Id
	@Column(name = "note_id", nullable = false, updatable = false)
	private UUID noteId;

	@Column(name = "brand_id", nullable = false, updatable = false)
	private UUID brandId;

	/**
	 * GHL's note id, or <strong>null on a delete marker</strong> ({@code V68}): the note was deleted
	 * before its push learned the id, and the drain must search the contact for it instead.
	 */
	@Column(name = "ghl_note_id")
	private String ghlNoteId;

	@Column(name = "ghl_contact_id", updatable = false)
	private String ghlContactId;

	@Column(name = "linked_at", nullable = false, updatable = false, insertable = false)
	private Instant linkedAt;

	protected OpportunityNoteGhlLink() {
		// for JPA
	}

	public OpportunityNoteGhlLink(UUID noteId, UUID brandId, String ghlNoteId, String ghlContactId) {
		this.noteId = noteId;
		this.brandId = brandId;
		this.ghlNoteId = ghlNoteId;
		this.ghlContactId = ghlContactId;
	}

	/** The contact the GHL note is on — needed to edit or delete it after the EvalOS row is gone. */
	public String getGhlContactId() {
		return ghlContactId;
	}

	public UUID getNoteId() {
		return noteId;
	}

	public UUID getBrandId() {
		return brandId;
	}

	public String getGhlNoteId() {
		return ghlNoteId;
	}

	public Instant getLinkedAt() {
		return linkedAt;
	}
}
