package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.service.ScopePredicate;

/**
 * Checklist items hang off a case and carry no scope columns of their own beyond
 * brand, so a caller narrows them by the case they already read.
 */
public interface DocumentChecklistItemRepository extends ScopedRepository<DocumentChecklistItem> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * Every item on one case. Not scoped on its own: only call it with a case id
	 * that came back from {@code CaseRepository.findScoped}, which already proved the
	 * caller may see it.
	 */
	List<DocumentChecklistItem> findByCaseId(UUID caseId);

	/**
	 * Every item on a page of cases, in one query rather than one per row.
	 *
	 * <p>Takes the brands as well as the case ids, so the query fails closed on its own.
	 * The predecessor took ids alone and carried a javadoc asking callers not to pass ids
	 * that came from a request — but a comment is not a scope, and the one thing standing
	 * between two brands should not be whether the next caller reads it. Pass the distinct
	 * brands of the cases the scoped read returned: a foreign case id then matches nothing
	 * instead of returning that brand's checklist.
	 */
	List<DocumentChecklistItem> findByBrandIdInAndCaseIdIn(Collection<UUID> brandIds, Collection<UUID> caseIds);
}
