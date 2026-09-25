package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

	List<ConversationMember> findByBrandIdAndConversationIdAndLeftAtIsNull(UUID brandId, UUID conversationId);

	List<ConversationMember> findByBrandIdAndConversationIdInAndLeftAtIsNull(UUID brandId,
			Collection<UUID> conversationIds);

	Optional<ConversationMember> findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(UUID brandId,
			UUID conversationId, ParticipantKind kind, UUID memberId);

	/**
	 * Inserts a current member unless one exists. {@code ON CONFLICT DO NOTHING} on the partial
	 * index, so a listener and the sweep racing on the same case insert one row, not a 500.
	 * @return 1 if inserted, 0 if already a member
	 */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO conversation_members (id, brand_id, conversation_id, member_kind, member_id, member_role, created_at)
			VALUES (gen_random_uuid(), :brandId, :conversationId, :kind, :memberId, :role, now())
			ON CONFLICT (conversation_id, member_kind, member_id) WHERE left_at IS NULL DO NOTHING
			""")
	int addIfAbsent(@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("conversationId") UUID conversationId,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("memberId") UUID memberId,
			@org.springframework.data.repository.query.Param("role") String role);
}
