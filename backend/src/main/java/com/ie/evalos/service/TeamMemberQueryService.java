package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped reads over staff. Exists so the scope predicate is applied here rather
 * than in a controller — the controller has no way to widen it, and no request
 * field can name a brand.
 */
@Service
public class TeamMemberQueryService {

	/**
	 * A member's own row is their "assignment", so a Self-tier caller sees only
	 * themselves.
	 */
	private static final ScopePredicate.Fields SCOPE =
			new ScopePredicate.Fields("brandId", "teamId", List.of("id"));

	private final TeamMemberRepository teamMembers;

	TeamMemberQueryService(TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
	}

	@Transactional(readOnly = true)
	public List<TeamMember> listForCaller() {
		return teamMembers.findAll(ScopePredicate.of(TenantContext.current(), SCOPE));
	}

	/**
	 * The people this caller could actually put on a case, in one role.
	 *
	 * <p>Separate from {@link #listForCaller} because it answers a different question and
	 * must be readable by a wider set of roles: a Project Manager assigns Case Managers and
	 * Coordinators but has no business reading the brand's staff directory. The same scope
	 * predicate applies, so a PM sees their team and a Brand Manager their brand — which is
	 * exactly the rule {@code assignCaseManager} enforces on the write side ("case manager
	 * is not on this case's team"), so the picker cannot offer somebody the transition would
	 * then refuse.
	 *
	 * <p>Inactive members are excluded here rather than filtered by the caller: an offer to
	 * assign somebody who has left is not a choice.
	 */
	@Transactional(readOnly = true)
	public List<TeamMember> assignable(Role role) {
		return teamMembers.findAll(ScopePredicate.<TeamMember>of(TenantContext.current(), SCOPE)
				.and((root, query, cb) -> cb.and(
						cb.equal(root.get("role"), role),
						cb.isTrue(root.get("active")))));
	}
}
