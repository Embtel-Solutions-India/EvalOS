package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Pushes waiting for a retry (V92). The drain's read is not brand-filtered, for the reason
 * {@link PushSubscriptionRepository}'s lookups are not: it is the system draining every brand's
 * queue, and each row carries the brand of the browser it is owed to.
 */
public interface PushOutboxRepository extends JpaRepository<PushRetry, UUID> {

	/** What is due, oldest first, capped so one pass stays short. */
	List<PushRetry> findTop100ByDueAtLessThanEqualOrderByDueAtAsc(Instant now);
}
