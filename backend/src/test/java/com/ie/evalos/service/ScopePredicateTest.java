package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.security.TenantContext;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Brand isolation is invariant 1, so it gets asserted directly rather than
 * inferred from an endpoint's status code: for each tier, exactly which columns
 * end up in the WHERE clause.
 */
class ScopePredicateTest {

	/**
	 * Two assignment columns, as {@code evalos_case} has: a case is one pipeline and the
	 * Coordinator and the Case Manager on it hold different slots.
	 */
	private static final ScopePredicate.Fields FIELDS =
			new ScopePredicate.Fields("brandId", "teamId", List.of("assignedTo", "assignedAlso"));

	private static final UUID MEMBER = UUID.randomUUID();
	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID TEAM = UUID.randomUUID();

	@SuppressWarnings("unchecked")
	private final Root<Object> root = mock(Root.class);
	private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
	private final Path<Object> brandPath = pathMock();
	private final Path<Object> teamPath = pathMock();
	private final Path<Object> assigneePath = pathMock();
	private final Path<Object> otherAssigneePath = pathMock();

	@SuppressWarnings("unchecked")
	private static Path<Object> pathMock() {
		return mock(Path.class);
	}

	@BeforeEach
	void stubPaths() {
		doReturn(brandPath).when(root).get("brandId");
		doReturn(teamPath).when(root).get("teamId");
		doReturn(assigneePath).when(root).get("assignedTo");
		doReturn(otherAssigneePath).when(root).get("assignedAlso");
		doReturn(mock(Predicate.class)).when(cb).equal(any(), any(Object.class));
	}

	private void applyAs(Role role, UUID brandId, UUID teamId) {
		ScopePredicate.<Object>of(new TenantContext(MEMBER, role, brandId, teamId), FIELDS)
				.toPredicate(root, null, cb);
	}

	@Test
	void gmReadsEveryBrandWithNoBrandPredicate() {
		applyAs(Role.GM, null, null);

		verify(cb).conjunction();
		verify(cb, never()).equal(any(), any(Object.class));
	}

	@Test
	void brandManagerIsLockedToOwnBrandOnly() {
		applyAs(Role.BRAND_MANAGER, BRAND, null);

		verify(cb).equal(brandPath, BRAND);
		verify(cb, never()).equal(teamPath, TEAM);
		verify(cb, never()).equal(assigneePath, MEMBER);
	}

	@Test
	void expertNetworkManagerReadsItsWholeBrand() {
		applyAs(Role.EXPERT_NETWORK_MANAGER, BRAND, null);

		verify(cb).equal(brandPath, BRAND);
		verify(cb, never()).equal(assigneePath, MEMBER);
	}

	@Test
	void projectManagerNarrowsToOwnTeamWithinTheBrand() {
		applyAs(Role.PROJECT_MANAGER, BRAND, TEAM);

		verify(cb).equal(brandPath, BRAND);
		verify(cb).equal(teamPath, TEAM);
	}

	@Test
	void caseManagerNarrowsToOwnAssignmentsWithinTheBrand() {
		applyAs(Role.CASE_MANAGER, BRAND, TEAM);

		verify(cb).equal(brandPath, BRAND);
		verify(cb).equal(assigneePath, MEMBER);
		verify(cb, never()).equal(teamPath, TEAM);
	}

	/**
	 * The regression this axis was widened to fix. With one assignment column a
	 * Coordinator matched nothing at all: their slot was not the column, so their board
	 * came back empty and the four transitions they are the actor for answered 403 on
	 * their own cases. Every slot is tested, and they are OR'd — a case naming them in
	 * either one is theirs.
	 */
	@Test
	void aSelfCallerMatchesEveryAssignmentSlotNotJustTheFirst() {
		applyAs(Role.PROJECT_COORDINATOR, BRAND, TEAM);

		verify(cb).equal(brandPath, BRAND);
		verify(cb).equal(assigneePath, MEMBER);
		verify(cb).equal(otherAssigneePath, MEMBER);
		// OR, not AND: requiring both slots would mean nobody ever matched.
		verify(cb).or(any(Predicate[].class));
		verify(cb, never()).equal(teamPath, TEAM);
	}

	/**
	 * An entity with no assignment column stays brand-wide for a Self caller rather than
	 * matching nothing: a Case Manager reads their brand's expert roster. Asserted so the
	 * empty list is visibly a decision — it is the one place this predicate does not
	 * narrow, and narrowing it would need a column the table does not have.
	 */
	@Test
	void aSelfCallerOnAnEntityWithNoAssignmentColumnStaysBrandWide() {
		ScopePredicate.<Object>of(new TenantContext(MEMBER, Role.CASE_MANAGER, BRAND, TEAM),
				ScopePredicate.Fields.brandOnly("brandId")).toPredicate(root, null, cb);

		verify(cb).equal(brandPath, BRAND);
		verify(cb, never()).or(any(Predicate[].class));
	}

	@Test
	void brandLockedRoleWithoutABrandMatchesNothing() {
		applyAs(Role.BRAND_MANAGER, null, null);

		// Fail closed: no brand on the principal must never mean "all brands".
		verify(cb).disjunction();
		verify(cb, never()).conjunction();
		verify(cb, never()).equal(any(), any(Object.class));
	}
}
