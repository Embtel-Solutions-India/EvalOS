package com.ie.evalos.chat;

import java.util.UUID;

import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One person's reaction to a message. Not history: un-reacting deletes the row. */
@Entity
@Table(name = "message_reactions")
public class MessageReaction extends ScopedEntity {

	@Column(name = "message_id", nullable = false, updatable = false)
	private UUID messageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reactor_kind", nullable = false, updatable = false)
	private ParticipantKind reactorKind;

	@Column(name = "reactor_id", nullable = false, updatable = false)
	private UUID reactorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reaction", nullable = false, updatable = false)
	private Reaction reaction;

	protected MessageReaction() {
		// for JPA
	}

	public MessageReaction(UUID brandId, UUID messageId, ParticipantKind reactorKind, UUID reactorId,
			Reaction reaction) {
		super(brandId);
		this.messageId = messageId;
		this.reactorKind = reactorKind;
		this.reactorId = reactorId;
		this.reaction = reaction;
	}

	public UUID getMessageId() {
		return messageId;
	}

	public ParticipantKind getReactorKind() {
		return reactorKind;
	}

	public UUID getReactorId() {
		return reactorId;
	}

	public Reaction getReaction() {
		return reaction;
	}
}
