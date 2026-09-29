package com.ie.evalos.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One comment on one draft version (Unit 58 §1). Immutable: every column is insert-only. */
@Entity
@Table(name = "draft_comments")
public class DraftComment extends ScopedEntity {

	public enum AuthorKind { STAFF, CLIENT }

	@Column(name = "document_id", nullable = false, updatable = false)
	private UUID documentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "author_kind", nullable = false, updatable = false)
	private AuthorKind authorKind;

	@Column(name = "author_id", nullable = false, updatable = false)
	private UUID authorId;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "page", updatable = false)
	private Integer page;

	protected DraftComment() {
		// for JPA
	}

	public DraftComment(UUID brandId, UUID documentId, AuthorKind authorKind, UUID authorId, String body,
			Integer page) {
		super(brandId);
		this.documentId = documentId;
		this.authorKind = authorKind;
		this.authorId = authorId;
		this.body = body;
		this.page = page;
	}

	public UUID getDocumentId() {
		return documentId;
	}

	public AuthorKind getAuthorKind() {
		return authorKind;
	}

	public UUID getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	public Integer getPage() {
		return page;
	}
}
