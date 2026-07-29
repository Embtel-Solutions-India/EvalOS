package com.ie.evalos.notification;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.stereotype.Component;

/**
 * Turns "who should hear about this" into member ids, for one case in one brand.
 *
 * <p>Every lookup names the brand except the GM's, which is brand-less by definition
 * — that is the only cross-brand read here, and it is narrowed to a single role so it
 * cannot become a general staff query. A member of another brand can never come back
 * from any method on this class.
 *
 * <p>Runs with **no** {@code TenantContext}: a listener fires inside the transition's
 * transaction, not inside a request, so there is no authenticated caller to scope by.
 * The brand comes from the case, which is the most authoritative source there is.
 */
@Component
public class RecipientResolver {

	private final TeamMemberRepository teamMembers;

	RecipientResolver(TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
	}

	/**
	 * The GM plus that brand's Brand Managers — who watch a pool nobody owns yet. No
	 * dedupe: a member holds one role, so the two queries cannot return the same row.
	 */
	public List<UUID> gmAndBrandManagers(UUID brandId) {
		return Stream.concat(
				gm().stream(),
				ids(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, brandId)).stream())
				.toList();
	}

	public List<UUID> gm() {
		return ids(teamMembers.findByActiveTrueAndRole(Role.GM));
	}

	/** That brand's Coordinators. Plural: the spec names the role, not one member. */
	public List<UUID> coordinators(UUID brandId) {
		return ids(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.PROJECT_COORDINATOR, brandId));
	}

	/**
	 * The case's own PM / CM. Empty when nobody is assigned yet rather than falling back
	 * to the brand's whole roster: an alert addressed to "whoever" is how a queue nobody
	 * reads gets built.
	 */
	public List<UUID> assignedPm(Case subject) {
		return single(subject.getAssignedPm());
	}

	public List<UUID> assignedCm(Case subject) {
		return single(subject.getAssignedCm());
	}

	private static List<UUID> single(UUID memberId) {
		return memberId == null ? List.of() : List.of(memberId);
	}

	private static List<UUID> ids(List<TeamMember> members) {
		return members.stream().map(TeamMember::getId).toList();
	}
}
