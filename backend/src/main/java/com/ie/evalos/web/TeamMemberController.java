package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.TeamMemberQueryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the scoping mechanism end to end: the role gate is here, the brand
 * filter is in the service, and there is no parameter that can name a brand.
 */
@RestController
@RequestMapping("/api/team-members")
public class TeamMemberController {

	/** No password hash, no email-adjacent secrets — projection is the DTO's job. */
	public record TeamMemberSummary(UUID id, String displayName, String email, Role role, UUID brandId, UUID teamId) {

		static TeamMemberSummary of(TeamMember member) {
			return new TeamMemberSummary(member.getId(), member.getDisplayName(), member.getEmail(),
					member.getRole(), member.getBrandId(), member.getTeamId());
		}
	}

	private final TeamMemberQueryService teamMembers;

	TeamMemberController(TeamMemberQueryService teamMembers) {
		this.teamMembers = teamMembers;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER')")
	public ApiResponse<List<TeamMemberSummary>> list() {
		return ApiResponse.ok(teamMembers.listForCaller().stream().map(TeamMemberSummary::of).toList());
	}

	/**
	 * A name and an id, and nothing else — what an assignment picker needs.
	 *
	 * <p>A deliberately separate, narrower projection from {@link TeamMemberSummary}: this is
	 * readable by a Project Manager, who assigns people but has no business reading the
	 * brand's staff directory, so it carries no email, brand or team. The scope still applies
	 * in the service, so a PM sees their team and nobody sees another brand.
	 */
	public record AssignableMember(UUID id, String displayName) {
	}

	@GetMapping("/assignable")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<List<AssignableMember>> assignable(@RequestParam Role role) {
		return ApiResponse.ok(teamMembers.assignable(role).stream()
				.map(member -> new AssignableMember(member.getId(), member.getDisplayName()))
				.toList());
	}

}
