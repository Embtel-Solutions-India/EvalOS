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
 * A reader's watermark in one conversation. Written only through
 * {@link MessageReadRepository#advance}, which never moves it backwards. {@code last_read_at} is the
 * <em>message's</em> timestamp, so "unread" is a pure comparison against message times.
 */
@Entity
@Table(name = "message_reads")
public class MessageRead extends ScopedEntity {

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reader_kind", nullable = false, updatable = false)
	private ParticipantKind readerKind;

	@Column(name = "reader_id", nullable = false, updatable = false)
	private UUID readerId;

	@Column(name = "last_read_message_id", nullable = false)
	private UUID lastReadMessageId;

	@Column(name = "last_read_at", nullable = false)
	private Instant lastReadAt;

	protected MessageRead() {
		// for JPA
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public ParticipantKind getReaderKind() {
		return readerKind;
	}

	public UUID getReaderId() {
		return readerId;
	}

	public UUID getLastReadMessageId() {
		return lastReadMessageId;
	}

	public Instant getLastReadAt() {
		return lastReadAt;
	}
}
