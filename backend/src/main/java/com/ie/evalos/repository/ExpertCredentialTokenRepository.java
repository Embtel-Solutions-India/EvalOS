package com.ie.evalos.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.domain.ExpertCredentialToken;
import com.ie.evalos.service.ScopePredicate;

/** {@code ClientCredentialTokenRepository}'s twin — see it for why each finder is scoped as it is. */
public interface ExpertCredentialTokenRepository extends ScopedRepository<ExpertCredentialToken> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** By hash, not brand: the brand is read off the row, never taken from the request. */
	Optional<ExpertCredentialToken> findByTokenHash(String tokenHash);

	/** An outstanding link — the rate limit on an unauthenticated route that sends mail. */
	Optional<ExpertCredentialToken> findFirstByExpertAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
			UUID expertAccountId, CredentialPurpose purpose, Instant now);

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Query("delete from ExpertCredentialToken t where t.expiresAt < :cutoff")
	int deleteExpiredBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff);
}
