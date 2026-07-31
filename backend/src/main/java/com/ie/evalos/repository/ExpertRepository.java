package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Expert;
import com.ie.evalos.service.ScopePredicate;

/**
 * The roster is a brand-wide resource: the Expert Network Manager's Supply tier
 * reads the whole brand, and no expert is shared across brands.
 *
 * <p><strong>The roster screen adds no query here.</strong> Search, tag, letter-type,
 * availability and tier filters are applied by {@code ExpertService} to the page
 * {@code findScoped} already returned, so scope stays decided in exactly one place
 * (the spec's "no new scoping code"). At the NFRs' scale — a brand's roster is tens of
 * rows, not tens of thousands — a filter in SQL would buy nothing and cost a second
 * read that could disagree with the scoped one about what the caller may see.
 */
public interface ExpertRepository extends ScopedRepository<Expert> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The sheet import's upsert key: the expert this row already is, if any.
	 *
	 * <p>Brand is a parameter rather than a scope predicate because the import resolves
	 * it once from the caller (or, for a GM, from the brand they named) and then
	 * upserts every row against that one brand — passing a {@code TenantContext} per
	 * row would re-derive the same answer fifty times.
	 *
	 * <p>Case-insensitive to match {@code uq_expert_per_brand_email}, which keys on
	 * {@code lower(email)}: the finder and the index expression have to agree or the
	 * index does not apply to this lookup's writes. And the index, not this read, is
	 * what actually enforces uniqueness — a lookup followed by an insert is a
	 * check-then-act that two concurrent uploads can both win.
	 */
	Optional<Expert> findByBrandIdAndEmailIgnoreCase(UUID brandId, String email);
}
