package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.TeamMemberQueryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
