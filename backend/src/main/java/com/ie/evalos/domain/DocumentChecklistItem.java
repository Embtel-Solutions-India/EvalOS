package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One required document on a case. The document itself is a Google Drive link on
 * the case; this row tracks only whether it has arrived and passed review.
 */
@Entity
@Table(name = "document_checklist_item")
public class DocumentChecklistItem extends ScopedEntity {

	@Column(name = "case_id")
	private UUID caseId;

	private String label;

	@Enumerated(EnumType.STRING)
	private ChecklistItemStatus status;

	@Column(name = "updated_at")
	private Instant updatedAt;

	protected DocumentChecklistItem() {
		// for JPA
	}

	public DocumentChecklistItem(UUID brandId, UUID caseId, String label, ChecklistItemStatus status) {
		super(brandId);
		this.caseId = caseId;
		this.label = label;
		this.status = status;
	}
}
