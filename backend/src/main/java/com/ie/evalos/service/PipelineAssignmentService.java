package com.ie.evalos.service;

import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts one GHL pipeline on one team member. GM-only, audited, and the single place the four rules
 * that make {@code ghl_pipeline_id} an access key rather than a text field are applied.
 *
 * <p><strong>Every refusal here is a 400 the caller can act on, and that is the whole reason this
 * service exists rather than the route writing straight to the repository.</strong> Each of these
 * is also a database constraint, and letting the constraint be the first line would answer 500
 * from a {@code DataIntegrityViolationException} — an error path a UI cannot test and a message
 * nobody can act on. The constraints stay as the backstop for the writers the enum cannot reach
 * (a seed, a hand-run UPDATE); this is the door humans come through.
 */
@Service
public class PipelineAssignmentService {

	private final TeamMemberRepository teamMembers;
	private final AuditService audit;
	private final String salesBrandId;

	PipelineAssignmentService(TeamMemberRepository teamMembers, AuditService audit,
			@Value("${evalos.ghl.sales-brand:}") String salesBrandId) {
		this.teamMembers = teamMembers;
		this.audit = audit;
		this.salesBrandId = salesBrandId;
	}

	/**
	 * Assigns {@code ghlPipelineId} to the member, or explains why not.
	 *
	 * @throws InvalidRequestException if the pipeline is blank, the member is not pipeline-scoped,
	 *                                 the member's brand is not the configured selling brand, or
	 *                                 another active member already holds the pipeline
	 */
	@Transactional
	public TeamMember assign(UUID memberId, String ghlPipelineId) {
		if (ghlPipelineId == null || ghlPipelineId.isBlank()) {
			// PUT sets; it does not clear. A null on a pipeline-scoped member would violate
			// `team_member_pipeline_matches_role`, and clearing only makes sense as part of a
			// role change or a deactivation — both of which are other routes' work.
			throw new InvalidRequestException(
					"A pipeline id is required. Clearing a pipeline is a role change or a deactivation.");
		}

		TeamMember member = teamMembers.findById(memberId)
				.orElseThrow(() -> new InvalidRequestException("No such team member"));

		if (!member.getRole().isPipelineScoped()) {
			throw new InvalidRequestException(
					"Only SALES and MARKETING members own a pipeline; " + member.getRole() + " does not");
		}

		requireSellingBrand(member);

		// Globally unique, not per brand, and the message says so because the alternative is a
		// GM re-trying the same id under a different brand. One GHL location means one pipeline
		// namespace: the same id under two brands would not be two pipelines, it would be one
		// pipeline read by two people who cannot see each other.
		teamMembers.findByGhlPipelineIdAndActiveTrue(ghlPipelineId)
				.filter((holder) -> !holder.getId().equals(memberId))
				.ifPresent((holder) -> {
					throw new InvalidRequestException(
							"That pipeline is already held by another active member. A pipeline has one owner.");
				});

		String before = member.getGhlPipelineId();
		member.assignPipeline(ghlPipelineId);
		TeamMember saved = teamMembers.save(member);

		// Audited because it changes what a person may see, which is the class of change
		// invariant 13 exists for. Before and after are the pipeline ids alone — the row also
		// carries an email and a password hash, and an audit payload is not a place for either.
		audit.recordEvent("TEAM_MEMBER", memberId, AuditAction.UPDATED,
				TenantContext.current().memberId(), before, ghlPipelineId);

		return saved;
	}

	/**
	 * Refuses a member of any brand but the one that owns {@code evalos.ghl.location-id}.
	 *
	 * <p>Unit 36's single-brand ceiling. EvalOS is multi-brand and talks to exactly one GHL
	 * sub-account, and a brand-locked role reading that location is what breaks invariant 1's
	 * stated exception. Naming the brand narrows the exception; refusing the mismatch here is
	 * what makes it a ceiling rather than a note in a document.
	 *
	 * <p>A blank property refuses <em>everyone</em>: an environment that has not been told which
	 * brand sells must not guess, and an empty board with no explanation is the failure this
	 * whole route exists to prevent.
	 */
	private void requireSellingBrand(TeamMember member) {
		if (salesBrandId == null || salesBrandId.isBlank()) {
			throw new InvalidRequestException(
					"No selling brand is configured. Set evalos.ghl.sales-brand to the brand that owns "
							+ "the configured GHL location before assigning a pipeline.");
		}
		if (member.getBrandId() == null || !member.getBrandId().toString().equals(salesBrandId)) {
			throw new InvalidRequestException(
					"Only the configured selling brand may hold a GHL pipeline. EvalOS reads one GHL "
							+ "location and cannot scope a second brand's pipelines until Unit 25.");
		}
	}
}
