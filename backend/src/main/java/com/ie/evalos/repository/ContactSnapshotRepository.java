package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

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

	/**
	 * The two upsert lookups for the GHL sync, which runs with no authenticated
	 * caller to scope by. Both take the brand as a parameter rather than deriving it,
	 * so neither can reach across brands: the same person in two brands is two
	 * snapshots, and a GHL contact id is only unique within its sub-account.
	 */
	Optional<ContactSnapshot> findByBrandIdAndGhlContactId(UUID brandId, String ghlContactId);

	Optional<ContactSnapshot> findByBrandIdAndEmailIgnoreCase(UUID brandId, String email);
}
