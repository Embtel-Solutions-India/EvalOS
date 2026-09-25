package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageReadRepository extends JpaRepository<MessageRead, UUID> {

	List<MessageRead> findByBrandIdAndConversationId(UUID brandId, UUID conversationId);

	/** Moves the watermark forward only: a stale tab cannot un-read a message. */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO message_reads (id, brand_id, conversation_id, reader_kind, reader_id,
			                           last_read_message_id, last_read_at, created_at)
			VALUES (gen_random_uuid(), :brandId, :conversationId, :kind, :readerId, :messageId, :messageAt, now())
			ON CONFLICT (conversation_id, reader_kind, reader_id) DO UPDATE
			  SET last_read_message_id = EXCLUDED.last_read_message_id, last_read_at = EXCLUDED.last_read_at
			  WHERE message_reads.last_read_at < EXCLUDED.last_read_at
			""")
	int advance(@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("conversationId") UUID conversationId,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("readerId") UUID readerId,
			@org.springframework.data.repository.query.Param("messageId") UUID messageId,
			@org.springframework.data.repository.query.Param("messageAt") java.time.Instant messageAt);
}
