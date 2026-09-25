package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	List<Conversation> findByBrandIdAndCaseId(UUID brandId, UUID caseId);

	Optional<Conversation> findByBrandIdAndCaseIdAndType(UUID brandId, UUID caseId, ConversationType type);

	/** Loaded by id only after the caller's access to it is proved — see ChatAccess. */
	Optional<Conversation> findByIdAndBrandId(UUID id, UUID brandId);

	List<Conversation> findByBrandIdAndCaseIdIn(UUID brandId, Collection<UUID> caseIds);

	/** Conversations still open on a case that has closed — the sweep's repair list. Across brands, as a system job. */
	@org.springframework.data.jpa.repository.Query("""
			SELECT c FROM Conversation c WHERE c.status = com.ie.evalos.chat.ConversationStatus.ACTIVE
			AND EXISTS (SELECT 1 FROM Case k WHERE k.id = c.caseId AND k.currentStage = com.ie.evalos.domain.Stage.CLOSED)
			""")
	List<Conversation> findActiveOfClosedCases();
}
