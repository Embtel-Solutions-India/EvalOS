package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ExpertAccount;
import com.ie.evalos.service.ScopePredicate;

/** Brand only, like {@code ClientAccountRepository}: an account belongs to a brand and nobody narrower. */
public interface ExpertAccountRepository extends ScopedRepository<ExpertAccount> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** The expert id has already come off a brand-scoped roster lookup; it never arrives from a request. */
	Optional<ExpertAccount> findByExpertId(UUID expertId);
}
