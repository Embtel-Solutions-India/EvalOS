package com.ie.evalos.chat;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One chat message (Unit 57 §2). Text only.
 *
 * <p>A delete keeps the row, clears the body and stamps {@code deleted_at}, so a reply still has
 * something to point at; the original text is in the audit trail. The generated {@code search}
 * column is deliberately not mapped.
 */
@Entity
@Table(name = "messages")
public class Message extends ScopedEntity {

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "author_kind", nullable = false, updatable = false)
	private ParticipantKind authorKind;

	@Column(name = "author_id", nullable = false, updatable = false)
	private UUID authorId;

	@Column(name = "body", nullable = false)
	private String body;

	@Column(name = "parent_message_id", updatable = false)
	private UUID parentMessageId;

	@Column(name = "edited_at")
	private Instant editedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Message() {
		// for JPA
	}

	public Message(UUID brandId, UUID conversationId, ParticipantKind authorKind, UUID authorId, String body,
			UUID parentMessageId) {
		super(brandId);
		this.conversationId = conversationId;
		this.authorKind = authorKind;
		this.authorId = authorId;
		this.body = body;
		this.parentMessageId = parentMessageId;
	}

	public void edit(String newBody, Instant at) {
		this.body = newBody;
		this.editedAt = at;
	}

	public void delete(Instant at) {
		this.body = "";
		this.deletedAt = at;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isAuthoredBy(ParticipantKind kind, UUID id) {
		return authorKind == kind && authorId.equals(id);
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public ParticipantKind getAuthorKind() {
		return authorKind;
	}

	public UUID getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	public UUID getParentMessageId() {
		return parentMessageId;
	}

	public Instant getEditedAt() {
		return editedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
