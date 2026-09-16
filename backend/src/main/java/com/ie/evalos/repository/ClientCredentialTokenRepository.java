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
	 * address can make EvalOS mail that inbox at the per-IP ceiling indefinitely. It bounds the
	 * table's growth rate; {@code PortalCleanupSweep} bounds its size. The predicate is exactly
	 * {@link ClientCredentialToken#isUsable}, expressed where the database can answer it.
	 *
	 * <p>Account-scoped rather than brand-scoped for the same reason {@link #findByTokenHash} is:
	 * the account id has already been resolved through a brand-scoped finder, and the brand is
	 * read off the row rather than supplied by a caller.
	 */
	Optional<ClientCredentialToken> findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
			UUID clientAccountId, CredentialPurpose purpose, Instant now);

	/**
	 * Drops every token that can no longer do anything — {@code PortalCleanupSweep}.
	 *
	 * <p><strong>Expiry is the only predicate, and it covers the spent ones too.</strong> A used
	 * token is already refused by {@link ClientCredentialToken#isUsable}, and it expires like any
	 * other within {@code credential-ttl}, so adding {@code used_at is not null} would delete the
	 * same rows a few minutes sooner in exchange for a second condition to reason about.
	 *
	 * <p><strong>Not brand-scoped, and that is the one place this differs from every other finder
	 * here.</strong> A sweep has no principal and no request — there is no brand to take one from,
	 * and taking the portal's configured one would silently leave a second brand's rows to grow
	 * forever the day the portal stops being single-brand. Cleanup is infrastructure; the rule it
	 * sits outside of is about reads a caller can influence.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.transaction.annotation.Transactional
	long deleteByExpiresAtBefore(Instant cutoff);
}
