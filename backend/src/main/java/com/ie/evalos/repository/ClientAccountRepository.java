package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.service.ScopePredicate;

/**
 * No team or assignee axis: a client account belongs to a brand and to nobody narrower, the
 * same shape as {@code ContactSnapshotRepository}.
 */
public interface ClientAccountRepository extends ScopedRepository<ClientAccount> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The one lookup sign-in needs. <strong>Brand-scoped</strong>, matching the
	 * {@code (brand_id, lower(email))} unique index — a query here without the brand would be a
	 * cross-brand read of a credential.
	 */
	Optional<ClientAccount> findByBrandIdAndEmailIgnoreCase(UUID brandId, String email);
}
