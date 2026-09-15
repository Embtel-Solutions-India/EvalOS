package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every refusal is a 400 the caller can act on, and that is what this class is for.
 *
 * <p><strong>Rewritten at Unit 44b, and one rule is gone rather than moved.</strong> The old
 * service refused a pipeline another active member already held — <em>"A pipeline has one
 * owner."</em> {@code 00d} §6.7 retires it: the target pipeline set includes <strong>Case Delivery,
 * which no single person owns</strong>, so a member holds a set and a pipeline may have several
 * members or none. What survives unchanged is the single-brand ceiling and the role gate.
 *
 * <p>The other change is what an assignment is addressed by: the <strong>mirror</strong> id, not a
 * pasted GHL string. {@code 00d} C4 is what the old shape cost — after the sub-account was replaced
 * every member was scoped to an id that no longer existed, and the board drew zero columns with no
 * error at all. A foreign key into {@code pipeline} makes that unrepresentable.
 */
class PipelineAssignmentServiceTest {

	private static final UUID SELLING_BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final UUID GM = UUID.randomUUID();
	private static final UUID PIPELINE = UUID.randomUUID();
	private static final String GHL_ID = "pipe_aditya_01";

	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final TeamMemberPipelineRepository assignments = mock(TeamMemberPipelineRepository.class);
	private final PipelineRepository pipelines = mock(PipelineRepository.class);
	private final AuditService audit = mock(AuditService.class);

	private PipelineAssignmentService service(String salesBrand) {
		return new PipelineAssignmentService(teamMembers, assignments, pipelines, audit, salesBrand);
	}

	private PipelineAssignmentService service() {
		return service(SELLING_BRAND.toString());
	}

	@BeforeEach
	void authenticateAsGm() {
		StaffPrincipal principal = new StaffPrincipal(GM, "gm@ie.test", "GM", Role.GM, null, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		when(assignments.ghlIdsFor(MEMBER)).thenReturn(List.of(GHL_ID));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/** Built through reflection because the entity has no public constructor — JPA owns it. */
	private static TeamMember member(Role role, UUID brandId) {
		TeamMember member = new TeamMember() {
		};
		ReflectionTestUtils.setField(member, "id", MEMBER);
		ReflectionTestUtils.setField(member, "role", role);
		ReflectionTestUtils.setField(member, "brandId", brandId);
		ReflectionTestUtils.setField(member, "active", true);
		return member;
	}

	private static Pipeline mirrored(UUID brandId, boolean live) {
		Pipeline pipeline = new Pipeline(brandId, GHL_ID, "Aditya's pipeline", 0);
		ReflectionTestUtils.setField(pipeline, "id", PIPELINE);
		if (!live) {
			pipeline.markMissing(java.time.Instant.now());
		}
		return pipeline;
	}

	private void givenMember(Role role, UUID brandId) {
		when(teamMembers.findById(MEMBER)).thenReturn(Optional.of(member(role, brandId)));
		when(pipelines.findById(PIPELINE)).thenReturn(Optional.of(mirrored(brandId, true)));
	}

	@Test
	void grantsThePipelineAndAuditsIt() {
		givenMember(Role.SALES, SELLING_BRAND);

		assertThat(service().grant(MEMBER, PIPELINE)).containsExactly(GHL_ID);

		verify(assignments).grant(MEMBER, PIPELINE, GM);
		// Audited because it changes what a person may see — invariant 13's class of change.
		verify(audit).recordEvent(eq("TEAM_MEMBER"), eq(MEMBER), eq(AuditAction.UPDATED), eq(GM), any(),
				any());
	}

	@Test
	void marketingIsGrantedTheSameWay() {
		givenMember(Role.MARKETING, SELLING_BRAND);

		assertThat(service().grant(MEMBER, PIPELINE)).containsExactly(GHL_ID);
	}

	/**
	 * <strong>The rule this slice deleted.</strong>
	 *
	 * <p>A second member on the same pipeline used to be refused — "A pipeline has one owner."
	 * {@code 00d} §6.7 retires it, because Case Delivery is a pipeline nobody owns and
	 * `uq_team_member_pipeline` could not express that. Nothing here asks who else is on it.
	 */
	@Test
	void aSecondMemberOnTheSamePipelineIsAllowed() {
		givenMember(Role.SALES, SELLING_BRAND);
		when(assignments.membersOn(PIPELINE)).thenReturn(List.of(UUID.randomUUID()));

		assertThat(service().grant(MEMBER, PIPELINE)).containsExactly(GHL_ID);
	}

	/**
	 * A pipeline GHL has stopped returning cannot be worked.
	 *
	 * <p>Assigning it would hand somebody a board that is empty for a reason no screen explains —
	 * {@code 00d} C4's symptom exactly, arrived at from the other direction.
	 */
	@Test
	void aMissingPipelineIsRefused() {
		when(teamMembers.findById(MEMBER)).thenReturn(Optional.of(member(Role.SALES, SELLING_BRAND)));
		when(pipelines.findById(PIPELINE)).thenReturn(Optional.of(mirrored(SELLING_BRAND, false)));

		assertThatThrownBy(() -> service().grant(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("no longer exists in GHL");

		verify(assignments, never()).grant(any(), any(), any());
	}

	/** An id that names no mirrored pipeline is a 400, which is C4 closed at the door. */
	@Test
	void anUnmirroredPipelineIsRefused() {
		when(teamMembers.findById(MEMBER)).thenReturn(Optional.of(member(Role.SALES, SELLING_BRAND)));
		when(pipelines.findById(PIPELINE)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().grant(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PIPELINE_MIRROR");
	}

	/**
	 * 400, not the 500 a {@code team_member_pipeline_matches_role} violation would produce.
	 * A UI cannot test an error path the database throws from.
	 */
	@Test
	void aNonPipelineScopedRoleIsRefused() {
		givenMember(Role.CASE_MANAGER, SELLING_BRAND);

		assertThatThrownBy(() -> service().grant(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("CASE_MANAGER");

		verify(assignments, never()).grant(any(), any(), any());
	}

	/** Unit 36 §4a: the single-brand ceiling is enforced, not documented. */
	@Test
	void aMemberOfAnotherBrandIsRefused() {
		givenMember(Role.SALES, OTHER_BRAND);

		assertThatThrownBy(() -> service().grant(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("selling brand");

		verify(assignments, never()).grant(any(), any(), any());
	}

	/** No selling brand configured means nobody may hold a pipeline yet. */
	@Test
	void noSellingBrandMeansNoAssignment() {
		givenMember(Role.SALES, SELLING_BRAND);

		assertThatThrownBy(() -> service("").grant(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("evalos.ghl.sales-brand");
	}

	/**
	 * <strong>Revoking may leave a member with none, and that is allowed.</strong>
	 *
	 * <p>The old model could not represent it — a pipeline-scoped member with no pipeline violated a
	 * CHECK. A join table has no such problem, and the empty state is one the code already handles
	 * correctly: {@code ScopePredicate}'s PIPELINE arm matches nothing and
	 * {@code PipelineScope.mine()} refuses with a sentence naming the fix. Failing closed is right
	 * for somebody mid-reassignment.
	 */
	@Test
	void revokingTheLastPipelineIsAllowedAndLeavesThemWithNone() {
		givenMember(Role.SALES, SELLING_BRAND);
		when(assignments.revoke(MEMBER, PIPELINE)).thenReturn(1);
		when(assignments.ghlIdsFor(MEMBER)).thenReturn(List.of());

		assertThat(service().revoke(MEMBER, PIPELINE)).isEmpty();
		verify(audit).recordEvent(eq("TEAM_MEMBER"), eq(MEMBER), eq(AuditAction.UPDATED), eq(GM), any(),
				any());
	}

	/** Revoking something they never had is a 400 rather than a silent success. */
	@Test
	void revokingAPipelineTheyAreNotOnIsRefused() {
		givenMember(Role.SALES, SELLING_BRAND);
		when(assignments.revoke(MEMBER, PIPELINE)).thenReturn(0);

		assertThatThrownBy(() -> service().revoke(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not on that pipeline");
	}

}
