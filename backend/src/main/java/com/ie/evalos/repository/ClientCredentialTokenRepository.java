package com.ie.evalos.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.domain.CredentialPurpose;
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

	/**
	 * An outstanding link for this account and purpose, if one is still good.
	 *
	 * <p><strong>This is a rate limit, not a convenience.</strong> {@code identify} and
	 * {@code forgot-password} are unauthenticated, so without it anyone who knows a client's
	 * address can make EvalOS mail that inbox at the per-IP ceiling indefinitely and grow this
	 * table without bound — there is no cleanup job. The predicate is exactly
	 * {@link ClientCredentialToken#isUsable}, expressed where the database can answer it.
	 *
	 * <p>Account-scoped rather than brand-scoped for the same reason {@link #findByTokenHash} is:
	 * the account id has already been resolved through a brand-scoped finder, and the brand is
	 * read off the row rather than supplied by a caller.
	 */
	Optional<ClientCredentialToken> findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
			UUID clientAccountId, CredentialPurpose purpose, Instant now);
}
