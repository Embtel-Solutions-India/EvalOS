package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One required document on a case. The document itself is an S3 object (Unit 30) reached from
 * the case; this row tracks only whether it has arrived and passed review.
 */
@Entity
@Table(name = "document_checklist_item")
public class DocumentChecklistItem extends ScopedEntity {

	@Column(name = "case_id")
	private UUID caseId;

	@Column(name = "label")
	private String label;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private ChecklistItemStatus status;

	@Column(name = "updated_at")
	private Instant updatedAt;

	/** Unit 61 (D60): when the PC or CM sent it to the client. Null = unsent, not in the portal. */
	@Column(name = "sent_at")
	private Instant sentAt;

	/** Who sent it. Null on a sent row means it was sent before D60 ({@code V75}'s backfill). */
	@Column(name = "sent_by")
	private UUID sentBy;

	protected DocumentChecklistItem() {
		// for JPA
	}

	/** Seeded from the service-type template at intake, one row per required document. */
	public DocumentChecklistItem(UUID brandId, UUID caseId, String label, ChecklistItemStatus status) {
		super(brandId);
		this.caseId = caseId;
		this.label = label;
		this.status = status;
		this.updatedAt = Instant.now();
	}

	/**
	 * The Coordinator's one write (Unit 10). Restamps {@code updated_at} in the same call,
	 * so a status can never be changed without the clock moving with it — which is what the
	 * board's "last touched" column reads.
	 */
	public void markStatus(ChecklistItemStatus status) {
		this.status = status;
		this.updatedAt = Instant.now();
	}

	/** Publishes the item to the client's portal. Once sent it stays sent; a second call is a no-op. */
	public void markSent(UUID by, Instant at) {
		if (sentAt == null) {
			this.sentAt = at;
			this.sentBy = by;
		}
	}

	public boolean isSent() {
		return sentAt != null;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public UUID getSentBy() {
		return sentBy;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public String getLabel() {
		return label;
	}

	public ChecklistItemStatus getStatus() {
		return status;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
