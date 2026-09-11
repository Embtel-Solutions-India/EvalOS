package com.ie.evalos.repository;

import java.util.Optional;

import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.service.ScopePredicate;

/**
 * No team or assignee axis: a credential token belongs to a brand and to nobody narrower.
 * {@code SCOPE} is declared even though {@code findByTokenHash} does not use it, because
 * {@code DomainInvariantsTest} requires every {@code ScopedEntity} to have one — see
 * {@code NotificationRepository} for the same reasoning.
 */
public interface ClientCredentialTokenRepository extends ScopedRepository<ClientCredentialToken> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * <strong>By hash, and deliberately not brand-scoped.</strong> The hash is 256 bits of
	 * {@code SecureRandom} and is globally unique; requiring a brand here would mean taking one
	 * from the request, which is exactly the input a caller must not control on a credential
	 * lookup. The brand is then read off the row that comes back.
	 */
	Optional<ClientCredentialToken> findByTokenHash(String tokenHash);
}
