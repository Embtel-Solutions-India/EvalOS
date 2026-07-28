package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.WebhookEvent;
import com.ie.evalos.domain.WebhookSource;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The gateway's own ledger. Not a {@code ScopedRepository}: this is read by the
 * transport before a tenant is known, and its rows are keyed by the source's id
 * rather than by brand.
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

	/** The idempotency lookup. A hit means this delivery has already been accepted. */
	Optional<WebhookEvent> findBySourceAndExternalId(WebhookSource source, String externalId);
}
