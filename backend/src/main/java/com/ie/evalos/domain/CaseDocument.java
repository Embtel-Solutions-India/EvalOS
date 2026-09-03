package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One version of one document on a case (Unit 31's table, Unit 32's code).
 *
 * <p><strong>Why this exists: a count is not a history.</strong> {@code evalos_case} carries
 * {@code draft_link} (one link) and {@code draft_version_count} (an integer), and between them
 * they cannot say who uploaded V2, when, what the PM said about it, or which version the client
 * approved — and the client-approved version is the one the expert signs and the business is paid
 * for.
 *
 * <p><strong>Append-only in spirit, with one mutable field.</strong> A version is written once;
 * {@link #status} and {@link #reviewComment} move when the PM rules on it, through
 * {@link #reviewed}. Everything else is {@code updatable = false}, so a resubmission is a new row
 * rather than an edit — which is the whole point of a version history.
 *
 * <p><strong>{@code objectKey} is null until Unit 30.</strong> There is no file store yet; the
 * draft is a link on the case. The row still earns its place today because the version, the
 * uploader, the timestamp and the review comment are the history, and none of them are the bytes.
 */
@Entity
@Table(name = "case_document")
public class CaseDocument extends ScopedEntity {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false)
	private DocumentKind kind;

	@Column(name = "version", nullable = false, updatable = false)
	private int version;

	/** The S3 object, once Unit 30 lands. Null until then. */
	@Column(name = "object_key")
	private String objectKey;

	@Column(name = "filename")
	private String filename;

	@Column(name = "content_type", updatable = false)
	private String contentType;

	@Column(name = "size_bytes", updatable = false)
	private Long sizeBytes;

	/** Null for a client or an expert, who have no {@code team_member} row. */
	@Column(name = "uploaded_by", updatable = false)
	private UUID uploadedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "uploaded_by_type", nullable = false, updatable = false)
	private ActorType uploadedByType;

	@Column(name = "uploaded_at", nullable = false, updatable = false)
	private Instant uploadedAt;

	/** What the uploader said about this version. Not the reviewer's answer — see below. */
	@Column(name = "notes", updatable = false)
	private String notes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private DocumentStatus status;

	/**
	 * The reviewer's comment on this version.
	 *
	 * <p>Distinct from {@link #notes}, which is the uploader's own description, and the two must
	 * never be merged: one is "here is what I changed", the other is "here is what to change", and
	 * a Case Manager reading the wrong one acts on the wrong instruction.
	 */
	@Column(name = "review_comment")
	private String reviewComment;

	/**
	 * SHA-256 of the bytes as received, hex (Unit 15). Null on every row that predates the
	 * document store, and on the {@code DRAFT} rows, which have no bytes.
	 */
	@Column(name = "content_sha256", updatable = false)
	private String contentSha256;

	/**
	 * The attestation the expert ticked, verbatim, and the name they were shown when they ticked
	 * it — set together with the hash and never afterwards.
	 *
	 * <p><strong>This is the evidence.</strong> With no signature provider there is no
	 * tamper-evident certificate, so what EvalOS can say is: this file hashes to this, a person
	 * named this, acting through this token, stated this sentence at this time. A boolean would
	 * carry none of it.
	 */
	@Column(name = "attestation", updatable = false)
	private String attestation;

	@Column(name = "attested_name", updatable = false)
	private String attestedName;

	protected CaseDocument() {
		// for JPA
	}

	public CaseDocument(UUID brandId, UUID caseId, DocumentKind kind, int version, UUID uploadedBy,
			ActorType uploadedByType, String notes) {
		super(brandId);
		this.caseId = caseId;
		this.kind = kind;
		this.version = version;
		this.uploadedBy = uploadedBy;
		this.uploadedByType = uploadedByType;
		this.notes = notes;
		this.uploadedAt = Instant.now();
		this.status = DocumentStatus.SUBMITTED;
	}

	/**
	 * Records a reviewer's ruling on this version.
	 *
	 * <p>The comment is optional on an approval and required on a return, and that asymmetry is
	 * deliberate: an approval needs no explanation to be actionable, while a rejection a Case
	 * Manager cannot act on is one they will have to ask about — and the asking happens outside
	 * the system, where the trail cannot see it. The requirement is enforced by the transition
	 * that calls this, not here, because "what makes a rejection usable" is a workflow rule.
	 */
	public void reviewed(DocumentStatus ruling, String comment) {
		this.status = ruling;
		this.reviewComment = comment == null || comment.isBlank() ? null : comment.strip();
	}

	/** Closes a version nobody will rule on, because a newer one replaced it. */
	public void superseded() {
		this.status = DocumentStatus.SUPERSEDED;
	}

	/**
	 * Records the provenance of a signed letter (Unit 15): the hash of what arrived, and the
	 * contemporaneous statement that it is this person's signature.
	 *
	 * <p>One method rather than three setters because the three are one fact and a row carrying
	 * a hash with no attestation would be a signed letter nobody claimed.
	 */
	public void attested(String contentSha256, String attestation, String attestedName) {
		this.contentSha256 = contentSha256;
		this.attestation = attestation;
		this.attestedName = attestedName;
	}

	public String getContentSha256() {
		return contentSha256;
	}

	public String getAttestation() {
		return attestation;
	}

	public String getAttestedName() {
		return attestedName;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public DocumentKind getKind() {
		return kind;
	}

	public int getVersion() {
		return version;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public void setObjectKey(String objectKey) {
		this.objectKey = objectKey;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
	}

	public ActorType getUploadedByType() {
		return uploadedByType;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public String getNotes() {
		return notes;
	}

	public DocumentStatus getStatus() {
		return status;
	}

	public String getReviewComment() {
		return reviewComment;
	}
}
