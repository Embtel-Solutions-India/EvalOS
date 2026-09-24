package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A note written in GHL, mirrored — Unit 47b.
 *
 * <p><strong>This is not {@link OpportunityNote} and the two must never merge.</strong>
 * {@code opportunity_note} is EvalOS staff prose: append-only by database trigger, EvalOS-owned,
 * pushed once to GHL and never edited there (Unit 54; 45e classifies it
 * {@code FieldOwnership.EVALOS}). The two are <em>shown</em> together on a deal and stored apart.
 * This is GHL's side — written in GHL's own UI, owned by GHL, read-only here. Merging them would put an
 * append-only trigger over rows a sync has to be able to update, and would make "who said this"
 * unanswerable on a screen that shows both.
 *
 * <p><strong>It hangs off a contact, and names an opportunity only as a convenience.</strong> A GHL
 * note belongs to a contact; the opportunity is how the mirror found it and is nullable, because a
 * contact can carry notes with no deal attached.
 *
 * <p>GHL's own author and timestamp are kept as given: a trail is only useful if it says when GHL
 * says something happened, not when EvalOS happened to read it.
 */
@Entity
@Table(name = "ghl_note")
public class GhlNote extends ScopedEntity {

	@Column(name = "ghl_id", nullable = false, updatable = false)
	private String ghlId;

	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "ghl_opportunity_id")
	private String ghlOpportunityId;

	@Column(name = "title")
	private String title;

	@Column(name = "body")
	private String body;

	@Column(name = "ghl_user_id")
	private String ghlUserId;

	@Column(name = "date_added")
	private Instant dateAdded;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	@Column(name = "missing_since")
	private Instant missingSince;

	protected GhlNote() {
		// for JPA
	}

	public GhlNote(UUID brandId, String ghlId, String ghlContactId, String ghlOpportunityId) {
		super(brandId);
		this.ghlId = ghlId;
		this.ghlContactId = ghlContactId;
		this.ghlOpportunityId = ghlOpportunityId;
		this.syncedAt = Instant.now();
	}

	/**
	 * GHL's current version of this note. Edited in GHL means edited here on the next pass.
	 *
	 * <p>{@code ghlOpportunityId} is assigned even when null (Unit 54): null means "the contact's,
	 * no deal's", and a note the mirror had filed under the wrong deal has to be able to move there.
	 */
	public void syncFromGhl(String title, String body, String ghlUserId, Instant dateAdded,
			String ghlOpportunityId) {
		this.title = title;
		this.body = body;
		this.ghlUserId = ghlUserId;
		this.dateAdded = dateAdded;
		this.ghlOpportunityId = ghlOpportunityId;
		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	/** Deleted in GHL. Stamped, never removed — a note that existed is a fact about the deal. */
	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	public String getGhlId() {
		return ghlId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getGhlUserId() {
		return ghlUserId;
	}

	public Instant getDateAdded() {
		return dateAdded;
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
