package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

	List<MessageReaction> findByBrandIdAndMessageIdIn(UUID brandId, Collection<UUID> messageIds);

	Optional<MessageReaction> findByBrandIdAndMessageIdAndReactorKindAndReactorIdAndReaction(UUID brandId,
			UUID messageId, ParticipantKind kind, UUID reactorId, Reaction reaction);
}
