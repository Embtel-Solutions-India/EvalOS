package com.ie.evalos.chat;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One of a case's three conversations. {@code UNIQUE (case_id, type)} keeps it to three. */
@Entity
@Table(name = "conversations")
public class Conversation extends ScopedEntity {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	private ConversationType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ConversationStatus status = ConversationStatus.ACTIVE;

	@Column(name = "read_only_at")
	private Instant readOnlyAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	protected Conversation() {
		// for JPA
	}

	public Conversation(UUID brandId, UUID caseId, ConversationType type) {
		super(brandId);
		this.caseId = caseId;
		this.type = type;
	}

	/** At {@code CLOSED}. Idempotent: a second call keeps the first timestamp and returns false. */
	public boolean makeReadOnly(Instant at) {
		if (status == ConversationStatus.READ_ONLY) {
			return false;
		}
		status = ConversationStatus.READ_ONLY;
		readOnlyAt = at;
		return true;
	}

	public void touch(Instant at) {
		lastMessageAt = at;
	}

	public boolean isReadOnly() {
		return status == ConversationStatus.READ_ONLY;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public ConversationType getType() {
		return type;
	}

	public ConversationStatus getStatus() {
		return status;
	}

	public Instant getReadOnlyAt() {
		return readOnlyAt;
	}

	public Instant getLastMessageAt() {
		return lastMessageAt;
	}
}
