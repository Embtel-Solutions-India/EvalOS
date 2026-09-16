package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which GHL pipelines a member may work — Unit 36's access key, made a set by Unit 44b.
 *
 * <p><strong>The one-owner rule is gone, and that is the change.</strong> It read: "That pipeline is
 * already held by another active member. A pipeline has one owner." {@code 00d} §6.7 retires it,
 * because the target pipeline set includes <strong>Case Delivery — a pipeline no single person
 * owns</strong>, which {@code uq_team_member_pipeline} could not express and
 * {@code PipelineScope.mine()} could not read. Many-to-many is the real shape, and "pretending
 * otherwise costs a second migration".
 *
 * <p><strong>Assignment is by mirror id, not by a pasted GHL string.</strong> That closes
 * {@code 00d} C4 structurally: every SALES/MARKETING member was scoped to a pipeline id that no
 * longer existed after the sub-account was replaced, and the symptom was "the board draws zero
 * columns <em>with no error</em>". A foreign key into {@code pipeline} makes that unrepresentable —
 * a pipeline has to be mirrored before anyone can be put on it.
 *
 * <p><strong>The single-brand ceiling survives untouched.</strong> Only a member of
 * {@code evalos.ghl.sales-brand} may hold a pipeline, because the location belongs to that brand
 * and a brand-locked role reading another brand's funnel is the leak invariant 1's exception is
 * narrowed to prevent.
 */
@Service
public class PipelineAssignmentService {

	private final TeamMemberRepository teamMembers;
	private final TeamMemberPipelineRepository assignments;
	private final PipelineRepository pipelines;
	private final AuditService audit;
	private final String salesBrandId;

	PipelineAssignmentService(TeamMemberRepository teamMembers, TeamMemberPipelineRepository assignments,
			PipelineRepository pipelines, AuditService audit,
			@Value("${evalos.ghl.sales-brand:}") String salesBrandId) {
		this.teamMembers = teamMembers;
		this.assignments = assignments;
		this.pipelines = pipelines;
		this.audit = audit;
		this.salesBrandId = salesBrandId;
	}

	/** One member's pipelines, as GHL ids — what the GM's screen lists and what a token carries. */
	@Transactional(readOnly = true)
	public List<String> assignedTo(UUID memberId) {
		return assignments.ghlIdsFor(memberId);
	}

	/**
	 * Puts a member on a pipeline.
	 *
	 * <p>Idempotent: granting a pipeline somebody already has changes nothing and is not an error.
	 * A GM clicking twice is not a mistake worth a 400.
	 *
	 * @param pipelineId the <strong>mirror</strong> id, not GHL's — see the class note on C4
	 */
	@Transactional
	public List<String> grant(UUID memberId, UUID pipelineId) {
		TeamMember member = pipelineScopedMember(memberId);
		Pipeline pipeline = mirrored(pipelineId);

		if (!pipeline.isLive()) {
			// A pipeline GHL has stopped returning cannot be worked. Assigning it would hand
			// somebody a board that is empty for a reason no screen explains.
			throw new InvalidRequestException("That pipeline no longer exists in GHL. "
					+ "Run the PIPELINE_MIRROR sweep if you think it should.");
		}
		if (!pipeline.getBrandId().equals(member.getBrandId())) {
			throw new InvalidRequestException("That pipeline belongs to another brand");
		}

		assignments.grant(memberId, pipelineId, TenantContext.current().memberId());
		List<String> after = assignments.ghlIdsFor(memberId);
		audit.recordEvent("TEAM_MEMBER", memberId, AuditAction.UPDATED,
				TenantContext.current().memberId(), null, "granted " + pipeline.getName());
		return after;
	}

	/**
	 * Takes a member off a pipeline.
	 *
	 * <p><strong>Nothing stops this leaving them with none.</strong> The old rule refused to clear
	 * the last one, because the column was {@code NOT NULL}-shaped by a CHECK and a pipeline-scoped
	 * member with no pipeline was unrepresentable. A join table has no such problem, and a member
	 * with no pipelines is a state the code already handles correctly:
	 * {@code ScopePredicate}'s PIPELINE arm matches nothing and {@code PipelineScope.mine()}
	 * refuses with a sentence naming the fix. Failing closed is the right behaviour for somebody
	 * mid-reassignment.
	 */
	@Transactional
	public List<String> revoke(UUID memberId, UUID pipelineId) {
		TeamMember member = pipelineScopedMember(memberId);
		Pipeline pipeline = mirrored(pipelineId);

		if (assignments.revoke(memberId, pipelineId) == 0) {
			throw new InvalidRequestException("That member is not on that pipeline");
		}
		audit.recordEvent("TEAM_MEMBER", memberId, AuditAction.UPDATED,
				TenantContext.current().memberId(), pipeline.getName(), null);
		return assignments.ghlIdsFor(member.getId());
	}

	private TeamMember pipelineScopedMember(UUID memberId) {
		TeamMember member = teamMembers.findById(memberId)
				.orElseThrow(() -> new InvalidRequestException("No such team member"));
		if (!member.getRole().isPipelineScoped()) {
			throw new InvalidRequestException(
					"Only SALES and MARKETING members work a pipeline; " + member.getRole() + " does not");
		}
		requireSellingBrand(member);
		return member;
	}

	private Pipeline mirrored(UUID pipelineId) {
		return pipelines.findById(pipelineId).orElseThrow(() -> new InvalidRequestException(
				"No such mirrored pipeline. Run the PIPELINE_MIRROR sweep if this is a new one."));
	}

	/**
	 * Unit 36's single-brand ceiling, enforced rather than documented.
	 *
	 * <p>{@code evalos.ghl.location-id} names one GHL sub-account and EvalOS cannot attribute it to
	 * a brand — invariant 1's one stated exception, which holds only because every screen over that
	 * location is GM-only. A brand-locked role reading it would void that argument, so
	 * {@code evalos.ghl.sales-brand} names the one brand whose members may.
	 */
	private void requireSellingBrand(TeamMember member) {
		if (salesBrandId == null || salesBrandId.isBlank()) {
			throw new InvalidRequestException(
					"No brand is configured as the selling brand (evalos.ghl.sales-brand), so no member "
							+ "may hold a pipeline yet.");
		}
		if (!UUID.fromString(salesBrandId).equals(member.getBrandId())) {
			throw new InvalidRequestException(
					"Only members of the selling brand may work a GHL pipeline. The configured GHL "
							+ "location belongs to one brand, and this member is not in it.");
		}
	}

}
