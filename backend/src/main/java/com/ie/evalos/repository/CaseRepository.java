package com.ie.evalos.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The one repository where all three scope axes are live: a Brand Manager reads the
 * brand, a Project Manager their team, and a Case Manager or Coordinator only the
 * cases assigned to them.
 *
 * <p>Two assignment columns, not one. A case is a single pipeline that a Coordinator
 * and a Case Manager work at different points, so "assigned to me" has to mean either
 * slot — with only {@code assigned_cm} declared, a Coordinator read matched nothing at
 * all and their board came back empty.
 */
public interface CaseRepository extends ScopedRepository<Case> {

	ScopePredicate.Fields SCOPE =
			new ScopePredicate.Fields("brandId", "teamId", List.of("assignedCm", "assignedCoordinator"));

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
	 * no authenticated caller — it comes from the endpoint token.
	 *
	 * <p>This read is not what enforces the rule: {@code V15}'s partial unique index is,
	 * because a lookup followed by an insert is a check-then-act that two concurrent
	 * deliveries can both win. The read is the fast path that turns a redelivery into a
	 * refresh; the index is what makes the race lose safely. {@code findFirst} with an
	 * explicit order because rows predating that index may still pair up, and picking the
	 * newest deterministically beats throwing where a paid case is waiting.
	 */
	Optional<Case> findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
			UUID brandId, UUID contactId, ServiceType serviceType, Stage excludedStage);

	/**
	 * How many cases each of these experts is carrying, and has finished, in one query
	 * for a whole roster page.
	 *
	 * <p>Native SQL rather than JPQL for two reasons: {@code FILTER (WHERE …)} is what
	 * makes this one grouped pass instead of two, and this entity's JPQL name is
	 * {@code Case}, which is also a JPQL keyword. The counts are what
	 * {@code ExpertLoadService} answers with — {@code expert.current_active_count} and
	 * {@code total_cases_completed} exist but have never been written by anything, so
	 * reading them would report every expert as free.
	 *
	 * <p>"Completed" excludes a refunded case, matching {@code RefundService.isRefunded}:
	 * a {@code CLOSED} case still holding {@code REFUND_REQUESTED} is one whose refund the
	 * GM approved, and it is not work delivered.
	 *
	 * <p><strong>Deliberately brand-unscoped</strong>, like
	 * {@code DocumentChecklistItemRepository.findByCaseIdIn}: it aggregates over expert
	 * ids the caller already read through {@code ExpertRepository.findScoped}. Do not
	 * call it with ids that came from a request. Asserted in
	 * {@code LocalPostgresIntegrationTest}, because a future caller passing an unscoped
	 * id would be a brand leak no mocked repository could show.
	 *
	 * @return one row per expert that has at least one case: {@code [expert_id, active,
	 *         completed]}. An expert with no cases is absent, not zero — the caller
	 *         supplies the zero.
	 */
	@Query(nativeQuery = true, value = """
			SELECT expert_id,
			       count(*) FILTER (WHERE current_stage <> 'CLOSED')                     AS active,
			       count(*) FILTER (WHERE current_stage = 'CLOSED'
			                          AND exception_state <> 'REFUND_REQUESTED')         AS completed
			  FROM evalos_case
			 WHERE expert_id IN (:expertIds)
			 GROUP BY expert_id
			""")
	List<Object[]> countCasesPerExpert(@Param("expertIds") Collection<UUID> expertIds);

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
			// A case with no deadline passes the filter. `deadline <= :dueBefore` alone is
			// SQL-correct and operationally wrong: `NULL <= x` is unknown, so an undated case
			// is dropped from every window — and since the board always sends a window, such
			// a case became invisible on every screen with no setting that revealed it.
			// Intake leaves the column null whenever GHL sends no date, so this is the normal
			// path, not an edge case. Undated work is unbounded-risk work; it belongs in any
			// answer to "what needs attention by then", never hidden by it.
			spec = spec.and((root, query, cb) -> cb.or(
					cb.isNull(root.get("deadline")),
					cb.lessThanOrEqualTo(root.get("deadline"), dueBefore)));
		}
		return findAll(spec);
	}
}
