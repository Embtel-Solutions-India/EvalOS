package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.CaseClientRemark;
import com.ie.evalos.service.ScopePredicate;

/** Remarks hang off a case, so a caller narrows them by a case it has already read. */
public interface CaseClientRemarkRepository extends ScopedRepository<CaseClientRemark> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** Oldest first. Only call with a case id that came back from an authorized load. */
	List<CaseClientRemark> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
