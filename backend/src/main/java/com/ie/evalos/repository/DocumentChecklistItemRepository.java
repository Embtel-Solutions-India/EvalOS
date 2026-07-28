package com.ie.evalos.repository;

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
}
