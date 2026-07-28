package com.ie.evalos.repository;

import java.time.Instant;
import java.util.List;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.domain.Specification;

/**
 * The one repository where all three scope axes are live: a Brand Manager reads
 * the brand, a Project Manager or Coordinator their team, and a Case Manager only
 * the cases assigned to them.
 */
public interface CaseRepository extends ScopedRepository<Case> {

	ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", "teamId", "assignedCm");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The board read: the caller's scope first, then the optional filters on top. A
	 * null filter is simply not applied — the scope is never optional, so no
	 * combination of parameters can widen it.
	 */
	default List<Case> findScoped(TenantContext ctx, Stage stage, SlaStatus slaStatus, Instant dueBefore) {
		Specification<Case> spec = ScopePredicate.of(ctx, SCOPE);
		if (stage != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("currentStage"), stage));
		}
		if (slaStatus != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("slaStatus"), slaStatus));
		}
		if (dueBefore != null) {
			spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("deadline"), dueBefore));
		}
		return findAll(spec);
	}
}
