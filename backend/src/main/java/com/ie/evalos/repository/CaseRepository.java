package com.ie.evalos.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ServiceType;
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
	 * The case a repeat delivery of the same contact belongs to, if there is one.
	 * Intake is create-or-update: one open case per contact per service, so a contact
	 * buying a second service opens a second case, and a contact coming back after the
	 * first case closed opens a new one.
	 *
	 * <p>Brand is a parameter rather than a scope predicate because Handoff A runs with
	 * no authenticated caller — it comes from the endpoint token. {@code findFirst}
	 * with an explicit order rather than a single-result finder: if two open cases ever
	 * exist for one pair, this has to pick the newest deterministically instead of
	 * throwing where a paid case is waiting.
	 */
	Optional<Case> findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
			UUID brandId, UUID contactId, ServiceType serviceType, Stage excludedStage);

	/**
	 * The board read: the caller's scope first, then the optional filters on top. A
	 * null filter is simply not applied — the scope is never optional, so no
	 * combination of parameters can widen it.
	 *
	 * <p>SLA status is deliberately not a filter here. It is derived from the clock, so
	 * the stored column is only as fresh as the last transition and a case can go
	 * overdue with nothing writing to it; filtering on it in SQL would silently miss
	 * exactly the rows a board asks for. {@code CaseLifecycleService.list} recomputes
	 * and then filters.
	 */
	default List<Case> findScoped(TenantContext ctx, Stage stage, Instant dueBefore) {
		Specification<Case> spec = ScopePredicate.of(ctx, SCOPE);
		if (stage != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("currentStage"), stage));
		}
		if (dueBefore != null) {
			spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("deadline"), dueBefore));
		}
		return findAll(spec);
	}
}
