package com.ie.evalos.repository;

import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.service.ScopePredicate;

/**
 * Contacts carry no team or assignee axis: everyone who may read a brand may read
 * that brand's contacts. Writes belong to the GHL sync only (invariant 7).
 */
public interface ContactSnapshotRepository extends ScopedRepository<ContactSnapshot> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}
}
