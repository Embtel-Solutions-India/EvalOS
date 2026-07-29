package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * A notification's assignee is its recipient, so a Self-tier caller reads only
 * their own. There is no team column: the notification centre filters by
 * recipient regardless of tier.
 *
 * <p><strong>The centre does not use {@code findScoped}.</strong> That applies the
 * caller's *tier*, and the GM's tier is ALL — so a GM's scoped read would return
 * every member's notifications in every brand. "My notifications" is not a scope
 * question, it is an identity one, so every finder below names `recipientId`
 * explicitly and there is no tier that widens it. The inherited {@code SCOPE} stays
 * declared because {@code DomainInvariantsTest} requires every {@code ScopedEntity}
 * to have one, and a later cross-recipient admin read would need it.
 */
public interface NotificationRepository extends ScopedRepository<Notification> {

	ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", null, "recipientId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** The centre's list: unread first, newest first within each half. */
	List<Notification> findByRecipientIdOrderByReadAscCreatedAtDesc(UUID recipientId, Pageable pageable);

	/** The badge. */
	long countByRecipientIdAndReadFalse(UUID recipientId);

	/**
	 * Mark-read's load. Recipient is in the query rather than checked afterwards, so
	 * another member's notification is indistinguishable from one that does not exist —
	 * the same reason `TeamMemberRepository.findByIdAndBrandIdAndRole` reads that way.
	 */
	Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

	/** Whether this case has already raised this kind of alert — see the pool listener. */
	boolean existsByCaseIdAndType(UUID caseId, NotificationType type);

	/**
	 * Read-all as one statement rather than loading every row to flip a boolean. Safe
	 * to do in bulk precisely because a notification carries no audit weight: the trail
	 * records what happened to the *case*, and whether somebody has looked at their
	 * bell is not part of it.
	 *
	 * <p>{@code @Transactional} here, not only on the calling service: a bulk update
	 * throws {@code TransactionRequiredException} without one, so a method that carries
	 * its own cannot be broken by a caller who forgets.
	 */
	@Modifying
	@Transactional
	@Query("update Notification n set n.read = true where n.recipientId = :recipientId and n.read = false")
	int markAllReadFor(@Param("recipientId") UUID recipientId);
}
