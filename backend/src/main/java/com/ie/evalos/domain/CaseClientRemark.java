package com.ie.evalos.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * What staff tell the client about their case (D74, {@code V85}) — append-only, and the only
 * free text on a case the client reads. Internal notes live elsewhere and never reach the portal.
 */
@Entity
@Table(name = "case_client_remark")
public class CaseClientRemark extends ScopedEntity implements CaseOwned {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Column(name = "author_id", nullable = false, updatable = false)
	private UUID authorId;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	protected CaseClientRemark() {
		// for JPA
	}

	public CaseClientRemark(UUID brandId, UUID caseId, UUID authorId, String body) {
		super(brandId);
		this.caseId = caseId;
		this.authorId = authorId;
		this.body = body;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public UUID getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	@Override
	public UUID liveCaseId() {
		return caseId;
	}
}
