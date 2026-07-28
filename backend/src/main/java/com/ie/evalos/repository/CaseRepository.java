package com.ie.evalos.repository;

import com.ie.evalos.domain.Case;
import com.ie.evalos.service.ScopePredicate;

/**
 * The one repository where all three scope axes are live: a Brand Manager reads
 * the brand, a Project Manager their team, and a Case Manager or Coordinator only
 * the cases assigned to them.
 */
public interface CaseRepository extends ScopedRepository<Case> {

	ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", "teamId", "assignedCm");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}
}
