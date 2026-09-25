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

	/**
	 * The last argument is {@code unteamedVisible}, and it is the pool (Unit 23): a case with
	 * no {@code team_id} has not been claimed by anybody, so a {@code TEAM} caller — the
	 * Project Manager — reads it alongside their own team's. Without it the PM inbox's
	 * <em>Unassigned</em> preset filtered a set that was empty for the only role that can
	 * reach the screen. Cases are the one place it is set; see {@code ScopePredicate.Fields}.
	 */
	ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", "teamId",
			List.of("assignedCm", "assignedCoordinator"), true);

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
	 * <p><strong>Deliberately brand-unscoped</strong>: it aggregates over expert ids the
	 * caller already read through {@code ExpertRepository.findScoped}. Do not call it with
	 * ids that came from a request. Asserted in {@code LocalPostgresIntegrationTest},
	 * because a future caller passing an unscoped id would be a brand leak no mocked
	 * repository could show.
	 *
	 * <p>This is now the <em>last</em> finder of that shape. The two it used to stand
	 * beside — the checklist and chase batch reads — were given brand predicates once it
	 * became clear a javadoc is not a scope. This one is the harder case, because it
	 * aggregates by expert and an expert is reachable from more than one brand's cases;
	 * narrowing it needs the counts split per brand first, which changes what it returns.
	 * Until then the convention is all there is, which is a reason to be wary of it.
	 *
	 * @return one row per expert that has at least one case: {@code [expert_id, active,
	 *         completed]}. An expert with no cases is absent, not zero — the caller
	 *         supplies the zero.
	 */
	/**
	 * Every case this contact has, newest first — the client party read (Unit 35, D1).
	 *
	 * <p><strong>Every case, including closed ones</strong>, which is why this is not one of the
	 * stage-filtered finders: a delivered case is precisely the one a client comes back for, and a
	 * list that hides it looks like lost work. {@code V38} adds the index this needs — V15's is
	 * partial on open cases and cannot serve it.
	 *
	 * <p>Brand-scoped in the signature rather than through {@code findScoped}, for the same reason
	 * {@code PortalAccessRepository} explains: a portal caller has no {@code TenantContext}. The
	 * brand comes off the token, which is the credential itself, so this is scoped by the thing
	 * that authenticated rather than by an ambient one.
	 */
	List<Case> findByBrandIdAndContactIdOrderByCreatedAtDesc(UUID brandId, UUID contactId);

	/**
	 * Every case this expert is on, newest first — the expert party read (Unit 35, D1).
	 *
	 * <p>Covered by {@code V5}'s {@code idx_case_brand_expert}. Same brand-off-the-token reasoning
	 * as the client finder above.
	 */
	List<Case> findByBrandIdAndExpertIdOrderByCreatedAtDesc(UUID brandId, UUID expertId);

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

	/**
	 * Every paid, active case at one stage — the finder the sweeps use (Unit 19).
	 *
	 * <p><strong>Brand-wide, and that is legitimate here rather than an oversight.</strong> A
	 * sweep has no authenticated caller, so there is no {@code TenantContext} to scope against —
	 * the same situation the inbound gateway is in. Saying so in the javadoc is the point: the
	 * old "do not call this with ids from a request" convention was retired in 2026-08 after a
	 * review observed that a comment is not a scope. What makes this safe is that a sweep has no
	 * caller to widen it for, and everything it <em>raises</em> carries the case's own brand.
	 *
	 * <p><strong>The {@code paid} predicate is in the query, not assumed.</strong> Case Creation
	 * v2.0 means every case is born paid, so it matches everything today and costs nothing —
	 * which is exactly why it is written down rather than left resting on a fact about intake
	 * that has already changed twice.
	 */
	@Query("select c from Case c where c.currentStage = :stage and c.paid = true")
	List<Case> findAllAtStageForSweep(@Param("stage") Stage stage);

	/**
	 * Every paid case that is still running — for the SLA sweep, which is not stage-specific.
	 *
	 * <p>Delivered and closed cases are excluded: no clock runs against them, and refreshing
	 * their {@code sla_status} would be rewriting history.
	 */
	/**
	 * Every case not at this stage, across brands — the chat reconcile sweep's list (Unit 57). A
	 * system sweep has no caller whose scope could apply; paid or not, because chat starts at
	 * creation.
	 */
	List<Case> findByCurrentStageNot(Stage stage);

	@Query("select c from Case c where c.paid = true and c.currentStage not in :terminal")
	List<Case> findActiveForSweep(@Param("terminal") Collection<Stage> terminal);
}
