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

	/**
	 * The idempotency lookup, matching the unique key exactly: brand included, because
	 * two brands are two GHL sub-accounts numbering their own invoices and may
	 * legitimately send the same external id. Without the brand this returns another
	 * brand's row — or, once two exist, more rows than an Optional can hold.
	 *
	 * <p>A hit means this brand has seen this delivery. Whether it was also
	 * <em>processed</em> is a separate question, and the difference is what makes a
	 * retry a retry rather than a duplicate.
	 */
	Optional<WebhookEvent> findBySourceAndBrandIdAndExternalId(WebhookSource source, UUID brandId, String externalId);
}
