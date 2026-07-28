package com.ie.evalos.service;

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

	private static final ScopePredicate.Fields FIELDS = new ScopePredicate.Fields("brandId", "teamId", "assignedTo");

	private static final UUID MEMBER = UUID.randomUUID();
	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID TEAM = UUID.randomUUID();

	@SuppressWarnings("unchecked")
	private final Root<Object> root = mock(Root.class);
	private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
	private final Path<Object> brandPath = mock(Path.class);
	private final Path<Object> teamPath = mock(Path.class);
	private final Path<Object> assigneePath = mock(Path.class);

	@BeforeEach
	void stubPaths() {
		doReturn(brandPath).when(root).get("brandId");
		doReturn(teamPath).when(root).get("teamId");
		doReturn(assigneePath).when(root).get("assignedTo");
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

	@Test
	void brandLockedRoleWithoutABrandMatchesNothing() {
		applyAs(Role.BRAND_MANAGER, null, null);

		// Fail closed: no brand on the principal must never mean "all brands".
		verify(cb).disjunction();
		verify(cb, never()).conjunction();
		verify(cb, never()).equal(any(), any(Object.class));
	}
}
