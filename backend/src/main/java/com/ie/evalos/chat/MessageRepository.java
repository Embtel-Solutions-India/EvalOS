package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findByIdAndBrandId(UUID id, UUID brandId);

	List<Message> findByBrandIdAndParentMessageIdOrderByCreatedAtAscIdAsc(UUID brandId, UUID parentMessageId);
}
