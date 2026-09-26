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
 * A participant's time in a conversation (Unit 57 §2).
 *
 * <p><strong>History, not state.</strong> The row is never deleted — a trigger refuses it — and
 * leaving stamps {@code left_at} once. Rejoining is a new row. {@code created_at} is the join time.
 */
@Entity
@Table(name = "conversation_members")
public class ConversationMember extends ScopedEntity {

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "member_kind", nullable = false, updatable = false)
	private ParticipantKind kind;

	@Column(name = "member_id", nullable = false, updatable = false)
	private UUID memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "member_role", nullable = false, updatable = false)
	private ChatRole role;

	@Column(name = "left_at")
	private Instant leftAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "left_reason")
	private LeftReason leftReason;

	protected ConversationMember() {
		// for JPA
	}

	public ConversationMember(UUID brandId, UUID conversationId, ParticipantKind kind, UUID memberId, ChatRole role) {
		super(brandId);
		this.conversationId = conversationId;
		this.kind = kind;
		this.memberId = memberId;
		this.role = role;
	}

	public void leave(LeftReason reason, Instant at) {
		if (leftAt != null) {
			throw new IllegalStateException("This participant has already left");
		}
		this.leftAt = at;
		this.leftReason = reason;
	}

	public boolean isCurrent() {
		return leftAt == null;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public ParticipantKind getKind() {
		return kind;
	}

	public UUID getMemberId() {
		return memberId;
	}

	public ChatRole getRole() {
		return role;
	}

	public Instant getJoinedAt() {
		return getCreatedAt();
	}

	public Instant getLeftAt() {
		return leftAt;
	}

	public LeftReason getLeftReason() {
		return leftReason;
	}
}
