package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.PipelineAssignmentService;
import com.ie.evalos.service.TeamMemberQueryService;


import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
	public record TeamMemberSummary(UUID id, String displayName, String email, Role role, UUID brandId, UUID teamId,
			/** Unit 68, for the staff directory. */
			boolean active, com.ie.evalos.domain.Segment segment, String ghlUserId) {

		static TeamMemberSummary of(TeamMember member) {
			return new TeamMemberSummary(member.getId(), member.getDisplayName(), member.getEmail(),
					member.getRole(), member.getBrandId(), member.getTeamId(), member.isActive(), member.getSegment(),
					member.getGhlUserId());
		}
	}

	/** Unit 68: a new member. The password is the GM's to set and hand over; no mail is sent. */
	public record CreateRequest(@jakarta.validation.constraints.NotBlank String displayName,
			@jakarta.validation.constraints.NotBlank String email, @jakarta.validation.constraints.NotNull Role role,
			UUID brandId, com.ie.evalos.domain.Segment segment, String ghlUserId,
			@jakarta.validation.constraints.NotBlank String password) {
	}

	public record UpdateRequest(@jakarta.validation.constraints.NotBlank String displayName,
			@jakarta.validation.constraints.NotBlank String email, @jakarta.validation.constraints.NotNull Role role,
			UUID brandId, com.ie.evalos.domain.Segment segment, String ghlUserId) {
	}

	public record ActiveRequest(boolean active) {
	}

	public record PasswordRequest(@jakarta.validation.constraints.NotBlank String password) {
	}

	private final TeamMemberQueryService teamMembers;
	private final PipelineAssignmentService pipelines;
	private final com.ie.evalos.service.TeamMemberAdminService admin;

	TeamMemberController(TeamMemberQueryService teamMembers, PipelineAssignmentService pipelines,
			com.ie.evalos.service.TeamMemberAdminService admin) {
		this.teamMembers = teamMembers;
		this.pipelines = pipelines;
		this.admin = admin;
	}

	// --- Unit 68: the GM's staff directory writes ------------------------------

	@org.springframework.web.bind.annotation.PostMapping
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<TeamMemberSummary> create(@RequestBody @jakarta.validation.Valid CreateRequest r) {
		return ApiResponse.ok(TeamMemberSummary.of(admin.create(new com.ie.evalos.service.TeamMemberAdminService.Details(
				r.displayName(), r.email(), r.role(), r.brandId(), r.segment(), r.ghlUserId()), r.password())));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<TeamMemberSummary> update(@PathVariable UUID id,
			@RequestBody @jakarta.validation.Valid UpdateRequest r) {
		return ApiResponse.ok(TeamMemberSummary.of(admin.update(id, new com.ie.evalos.service.TeamMemberAdminService.Details(
				r.displayName(), r.email(), r.role(), r.brandId(), r.segment(), r.ghlUserId()))));
	}

	@PutMapping("/{id}/active")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<TeamMemberSummary> setActive(@PathVariable UUID id, @RequestBody ActiveRequest r) {
		return ApiResponse.ok(TeamMemberSummary.of(admin.setActive(id, r.active())));
	}

	@PutMapping("/{id}/password")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<Void> setPassword(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid PasswordRequest r) {
		admin.setPassword(id, r.password());
		return ApiResponse.ok(null);
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

	/** The mirrored pipeline, by EvalOS's id — never a pasted GHL string. See below. */
	public record PipelineGrantRequest(@jakarta.validation.constraints.NotNull UUID pipelineId) {
	}

	/** What the assignment screen shows back: no email, no hash, no brand directory. */
	public record PipelineAssignment(UUID id, List<String> ghlPipelineIds) {
	}

	/**
	 * Puts a member on a GHL pipeline, or takes them off one. GM-only, audited, every refusal a 400.
	 *
	 * <p><strong>A set, not a value, as of Unit 44b.</strong> This was
	 * {@code PUT /{id}/ghl-pipeline} taking one id and replacing whatever was there.
	 * {@code 00d} §6.7 retires the one-owner model: the target pipeline set includes
	 * <strong>Case Delivery, which no single person owns</strong>, so a member holds a set and the
	 * verbs are grant and revoke.
	 *
	 * <p><strong>Addressed by the MIRROR id, which is the point.</strong> The old route took GHL's
	 * opaque string, and {@code 00d} C4 is what that cost: after the sub-account was replaced every
	 * member was scoped to an id that no longer existed, and "the board draws zero columns
	 * <em>with no error</em>". The mirror is a foreign key, so an id that does not name a real
	 * pipeline is a 400 at assignment rather than an empty board weeks later.
	 * {@code GET /api/ghl/pipelines} is the list to pick from.
	 *
	 * <p>GM-only because this changes what a person may see, and because the pipeline namespace is
	 * global — a Brand Manager cannot be shown, let alone reassign, a pipeline that may belong to
	 * another brand's desk.
	 */
	@PutMapping("/{id}/pipelines")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<PipelineAssignment> grantPipeline(@PathVariable UUID id,
			@RequestBody @jakarta.validation.Valid PipelineGrantRequest request) {
		return ApiResponse.ok(new PipelineAssignment(id, pipelines.grant(id, request.pipelineId())));
	}

	@DeleteMapping("/{id}/pipelines/{pipelineId}")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<PipelineAssignment> revokePipeline(@PathVariable UUID id,
			@PathVariable UUID pipelineId) {
		return ApiResponse.ok(new PipelineAssignment(id, pipelines.revoke(id, pipelineId)));
	}

	@GetMapping("/{id}/pipelines")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<PipelineAssignment> pipelinesOf(@PathVariable UUID id) {
		return ApiResponse.ok(new PipelineAssignment(id, pipelines.assignedTo(id)));
	}

}
