package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.service.ScopePredicate;

/**
 * A case's document versions.
 *
 * <p><strong>Brand-only scoping, and the case's own scope is what really guards these rows.</strong>
 * Every read here is reached through a case the caller already loaded through
 * {@code CaseRepository.findScoped}, which applies the full brand/team/assignee predicate. Adding
 * an assignee axis to this repository would be a second, weaker copy of that rule — and a document
 * has no assignee of its own to filter on.
 */
public interface CaseDocumentRepository extends ScopedRepository<CaseDocument> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	/**
	 * Required by {@link ScopedRepository}, and not optional in any sense that matters: without it
	 * Spring Data treats {@code scopeFields} as a derived query and refuses to create the bean at
	 * startup. A missing scope declaration fails the boot rather than quietly reading every brand.
	 */
	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** The version history for one kind, newest first. The read the case page draws. */
	List<CaseDocument> findByCaseIdAndKindOrderByVersionDesc(UUID caseId, DocumentKind kind);

	/**
	 * The most recent version of one kind, which is the one a review rules on.
	 *
	 * <p>Ordered by {@code version} rather than by {@code uploadedAt}: the version number is the
	 * sequence, and two rows written in the same millisecond would order arbitrarily by time.
	 */
	Optional<CaseDocument> findFirstByCaseIdAndKindOrderByVersionDesc(UUID caseId, DocumentKind kind);
}
