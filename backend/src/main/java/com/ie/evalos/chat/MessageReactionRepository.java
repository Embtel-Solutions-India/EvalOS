package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

	/**
	 * The reactions on these messages. Not brand-filtered: the ids always come from a message read
	 * that was, and a GM's search spans brands.
	 */
	List<MessageReaction> findByMessageIdIn(Collection<UUID> messageIds);

	Optional<MessageReaction> findByBrandIdAndMessageIdAndReactorKindAndReactorIdAndReaction(UUID brandId,
			UUID messageId, ParticipantKind kind, UUID reactorId, Reaction reaction);
}
