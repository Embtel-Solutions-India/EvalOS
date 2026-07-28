package com.ie.evalos.service;

import java.util.List;

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
	private static final ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", "teamId", "id");

	private final TeamMemberRepository teamMembers;

	TeamMemberQueryService(TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
	}

	@Transactional(readOnly = true)
	public List<TeamMember> listForCaller() {
		return teamMembers.findAll(ScopePredicate.of(TenantContext.current(), SCOPE));
	}
}
