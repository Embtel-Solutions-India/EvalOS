package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Segment;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.PipelineAssignmentService;
import com.ie.evalos.service.TeamMemberQueryService;

import jakarta.validation.constraints.NotBlank;

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
	private final PipelineAssignmentService pipelines;

	TeamMemberController(TeamMemberQueryService teamMembers, PipelineAssignmentService pipelines) {
		this.teamMembers = teamMembers;
		this.pipelines = pipelines;
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

	/** Only the pipeline. A role change and a deactivation are different operations. */
	public record AssignPipelineRequest(@NotBlank String ghlPipelineId) {
	}

	/** What the assignment screen shows back: no email, no hash, no brand directory. */
	public record PipelineAssignment(UUID id, Role role, Segment segment, String ghlPipelineId) {
	}

	/**
	 * Puts one GHL pipeline on one member. GM-only, audited, and every refusal is a 400.
	 *
	 * <p>GM-only because this changes what a person may see, and because the pipeline namespace
	 * is global — a Brand Manager cannot be shown, let alone allowed to reassign, a pipeline that
	 * may belong to another brand's desk. The rules it enforces are in
	 * {@link PipelineAssignmentService}.
	 */
	@PutMapping("/{id}/ghl-pipeline")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<PipelineAssignment> assignPipeline(@PathVariable UUID id,
			@RequestBody @jakarta.validation.Valid AssignPipelineRequest request) {
		TeamMember member = pipelines.assign(id, request.ghlPipelineId());
		return ApiResponse.ok(new PipelineAssignment(
				member.getId(), member.getRole(), member.getSegment(), member.getGhlPipelineId()));
	}

}
