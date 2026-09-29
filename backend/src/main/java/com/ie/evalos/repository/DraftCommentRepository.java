package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.DraftComment;
import com.ie.evalos.service.ScopePredicate;

/**
 * Draft comments (Unit 58). Brand only, like {@link CaseDocumentRepository}: a comment is reached
 * through its version, which is reached through an already-authorized case.
 *
 * <p>The inherited update and delete methods exist but can never succeed — the V70 trigger refuses
 * both, so a comment stays the record it was written as.
 */
public interface DraftCommentRepository extends ScopedRepository<DraftComment> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	List<DraftComment> findByBrandIdAndDocumentIdOrderByCreatedAtAsc(UUID brandId, UUID documentId);
}
