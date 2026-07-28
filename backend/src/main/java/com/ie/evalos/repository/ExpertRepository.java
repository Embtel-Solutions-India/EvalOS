package com.ie.evalos.repository;

import com.ie.evalos.domain.Expert;
import com.ie.evalos.service.ScopePredicate;

/**
 * The roster is a brand-wide resource: the Expert Network Manager's Supply tier
 * reads the whole brand, and no expert is shared across brands.
 */
public interface ExpertRepository extends ScopedRepository<Expert> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}
}
