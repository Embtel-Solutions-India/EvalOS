package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A document the client sent <strong>with the request</strong>, before any case existed — Unit 53
 * (D33, {@code 53-request-documents.md}).
 *
 * <p><strong>It shares its S3 object with the {@link CaseDocument} it becomes.</strong> The key is
 * {@code DocumentStore.clientKey(brand, ghlContactId, …)} — the contact's prefix, not the
 * application's and not the case's — so Handoff A carries a document onto the case by writing a
 * second row that points at the same {@code object_key}. Nothing copies in S3 and nothing re-keys,
 * because the key was never case-scoped to begin with. {@link #carriedToCaseDocumentId} records
 * that it happened, which is what makes a replayed {@code opportunity.won} skip rather than
 * duplicate.
 *
 * <p><strong>No status, no review, no checklist item</strong>, unlike its sibling. A request has no
 * checklist — that is a case concept and Unit 10's — and chasing a missing document at request
 * stage is Sales' conversation rather than a state machine ({@code 53} §5). Submit is never gated
 * on completeness ({@code 43} §5, unchanged).
 */
@Entity
@Table(name = "application_document")
public class ApplicationDocument extends ScopedEntity {

	@Column(name = "client_application_id", nullable = false, updatable = false)
	private UUID clientApplicationId;

	/**
	 * The contact the documents belong to, as a real foreign key.
	 *
	 * <p>D41 keys the S3 prefix by the <em>GHL</em> contact id; this is the {@code contact_snapshot}
	 * row, and the GHL id is reached through it. Storing the row rather than the string is what
	 * keeps a contact whose GHL id is backfilled later from orphaning a document (D18: naming a
	 * contact by GHL's id does not change a primary key).
	 */
	@Column(name = "contact_id", nullable = false, updatable = false)
	private UUID contactId;

	/** Authoritative for reads. Never rebuilt from the other columns — see the class note. */
	@Column(name = "object_key", nullable = false, updatable = false)
	private String objectKey;

	/** The real name, which is data. A filename that reached a key would let a client pick a prefix. */
	@Column(name = "filename", nullable = false, updatable = false)
	private String filename;

	@Column(name = "content_type", updatable = false)
	private String contentType;

	@Column(name = "size_bytes", updatable = false)
	private Long sizeBytes;

	@Column(name = "uploaded_at", nullable = false, updatable = false)
	private Instant uploadedAt;

	@Column(name = "uploaded_by_client", nullable = false, updatable = false)
	private boolean uploadedByClient;

	@Column(name = "carried_to_case_document_id")
	private UUID carriedToCaseDocumentId;

	protected ApplicationDocument() {
		// for JPA
	}

	public ApplicationDocument(UUID brandId, UUID clientApplicationId, UUID contactId, String objectKey,
			String filename, String contentType, Long sizeBytes) {
		super(brandId);
		this.clientApplicationId = clientApplicationId;
		this.contactId = contactId;
		this.objectKey = objectKey;
		this.filename = filename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.uploadedAt = Instant.now();
		this.uploadedByClient = true;
	}

	/**
	 * Records that Handoff A put this document on the case.
	 *
	 * <p><strong>Write-once, and that is the idempotency.</strong> {@code opportunity.won} is the
	 * only door into a case (invariant 8) and GHL may deliver it more than once; a second pass finds
	 * this set, skips, and the case ends with one {@code case_document} per request document rather
	 * than two.
	 */
	public void carriedTo(UUID caseDocumentId) {
		if (this.carriedToCaseDocumentId == null) {
			this.carriedToCaseDocumentId = caseDocumentId;
		}
	}

	public boolean isCarried() {
		return carriedToCaseDocumentId != null;
	}

	public UUID getClientApplicationId() {
		return clientApplicationId;
	}

	public UUID getContactId() {
		return contactId;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public String getFilename() {
		return filename;
	}

	public String getContentType() {
		return contentType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public boolean isUploadedByClient() {
		return uploadedByClient;
	}

	public UUID getCarriedToCaseDocumentId() {
		return carriedToCaseDocumentId;
	}
}
