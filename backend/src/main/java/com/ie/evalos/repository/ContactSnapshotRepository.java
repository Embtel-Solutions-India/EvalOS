package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.service.ScopePredicate;

/**
 * Contacts carry no team or assignee axis: everyone who may read a brand may read
 * that brand's contacts. Writes come from Handoff A's contact sync, plus the write-once
 * GHL-id backfill on {@code ContactSnapshot} — never from an EvalOS business rule
 * mutating a synced field (invariant 7).
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

	/**
	 * Every contact in this brand with this address — <strong>a list, because the column is not
	 * unique.</strong>
	 *
	 * <p>{@code uq_contact_per_brand_email} (V27) is a <em>partial</em> index:
	 * {@code WHERE email IS NOT NULL AND ghl_contact_id IS NULL}. A row that carries GHL's id is
	 * deliberately outside it, because such a row does not need an address to tell it apart — so
	 * two mirrored contacts sharing a firm's office inbox are legal and expected.
	 *
	 * <p><strong>This returned an {@code Optional} until 2026-09-23</strong>, which was survivable
	 * only while the table held the few dozen contacts EvalOS had met by itself. The first full
	 * contact mirror pass found a shared address in the real location and the finder threw
	 * {@code IncorrectResultSizeDataAccessException} mid-sweep. The type was wrong, not the data:
	 * {@code ContactSnapshotService}'s own note has always said "two distinct GHL contacts can
	 * share an inbox — a firm's office address is the obvious case".
	 */
	List<ContactSnapshot> findByBrandIdAndEmailIgnoreCase(UUID brandId, String email);
}
