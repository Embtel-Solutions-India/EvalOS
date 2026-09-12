package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
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
 * <p>Each of these rules is <em>also</em> a database constraint, pinned in
 * {@code LocalPostgresIntegrationTest}. The constraint is the backstop for the writers the enum
 * cannot reach; these tests pin the door humans come through, where the difference between 400
 * and a 500 out of a {@code DataIntegrityViolationException} is the difference between a message
 * a GM can act on and one nobody can.
 */
class PipelineAssignmentServiceTest {

	private static final UUID SELLING_BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final UUID GM = UUID.randomUUID();
	private static final String PIPELINE = "pipe_aditya_01";

	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);

	private PipelineAssignmentService service(String salesBrand) {
		return new PipelineAssignmentService(teamMembers, audit, salesBrand);
	}

	private PipelineAssignmentService service() {
		return service(SELLING_BRAND.toString());
	}

	@BeforeEach
	void authenticateAsGm() {
		StaffPrincipal principal = new StaffPrincipal(GM, "gm@ie.test", "GM", Role.GM, null, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
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

	private void givenMember(Role role, UUID brandId) {
		TeamMember member = member(role, brandId);
		when(teamMembers.findById(MEMBER)).thenReturn(Optional.of(member));
		when(teamMembers.save(any(TeamMember.class))).thenAnswer((call) -> call.getArgument(0));
		when(teamMembers.findByGhlPipelineIdAndActiveTrue(any())).thenReturn(Optional.empty());
	}

	@Test
	void assignsThePipelineAndAuditsIt() {
		givenMember(Role.SALES, SELLING_BRAND);

		TeamMember saved = service().assign(MEMBER, PIPELINE);

		assertThat(saved.getGhlPipelineId()).isEqualTo(PIPELINE);
		// Audited because it changes what a person may see — invariant 13's class of change.
		verify(audit).recordEvent(eq("TEAM_MEMBER"), eq(MEMBER), eq(AuditAction.UPDATED), eq(GM), eq(null),
				eq(PIPELINE));
	}

	@Test
	void marketingIsAssignedTheSameWay() {
		givenMember(Role.MARKETING, SELLING_BRAND);

		assertThat(service().assign(MEMBER, PIPELINE).getGhlPipelineId()).isEqualTo(PIPELINE);
	}

	/** PUT sets; it does not clear. Clearing is a role change or a deactivation. */
	@Test
	void aBlankPipelineIsRefusedBeforeTheMemberIsEvenLoaded() {
		assertThatThrownBy(() -> service().assign(MEMBER, "  "))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("required");

		verify(teamMembers, never()).findById(any());
	}

	@Test
	void aNullPipelineIsRefused() {
		assertThatThrownBy(() -> service().assign(MEMBER, null))
				.isInstanceOf(InvalidRequestException.class);
	}

	/**
	 * 400, not the 500 a {@code team_member_pipeline_matches_role} violation would produce.
	 * A UI cannot test an error path the database throws from.
	 */
	@Test
	void aNonPipelineScopedRoleIsRefused() {
		givenMember(Role.CASE_MANAGER, SELLING_BRAND);

		assertThatThrownBy(() -> service().assign(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("CASE_MANAGER");

		verify(teamMembers, never()).save(any());
	}

	/** Unit 36 §4a: the single-brand ceiling is enforced, not documented. */
	@Test
	void aMemberOfAnotherBrandIsRefused() {
		givenMember(Role.SALES, OTHER_BRAND);

		assertThatThrownBy(() -> service().assign(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("selling brand");

		verify(teamMembers, never()).save(any());
	}

	/**
	 * An environment that has not been told which brand sells refuses everyone.
	 *
	 * <p>The safe direction: guessing would put a brand-locked role on a location EvalOS cannot
	 * attribute, which is exactly the hole invariant 1's exception is held shut by.
	 */
	@Test
	void noConfiguredSellingBrandRefusesEveryone() {
		givenMember(Role.SALES, SELLING_BRAND);

		assertThatThrownBy(() -> service("").assign(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("evalos.ghl.sales-brand");
	}

	/**
	 * One pipeline, one owner — checked across every brand, because
	 * {@code uq_team_member_pipeline} is global.
	 */
	@Test
	void aPipelineAlreadyHeldByAnotherActiveMemberIsRefused() {
		givenMember(Role.SALES, SELLING_BRAND);
		TeamMember holder = member(Role.SALES, OTHER_BRAND);
		ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
		when(teamMembers.findByGhlPipelineIdAndActiveTrue(PIPELINE)).thenReturn(Optional.of(holder));

		assertThatThrownBy(() -> service().assign(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("one owner");

		verify(teamMembers, never()).save(any());
	}

	/** Re-assigning a member the pipeline they already hold is not a collision with themselves. */
	@Test
	void reassigningTheSamePipelineToItsOwnHolderIsAllowed() {
		givenMember(Role.SALES, SELLING_BRAND);
		TeamMember self = member(Role.SALES, SELLING_BRAND);
		when(teamMembers.findByGhlPipelineIdAndActiveTrue(PIPELINE)).thenReturn(Optional.of(self));

		assertThat(service().assign(MEMBER, PIPELINE).getGhlPipelineId()).isEqualTo(PIPELINE);
	}

	@Test
	void anUnknownMemberIsRefused() {
		when(teamMembers.findById(MEMBER)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().assign(MEMBER, PIPELINE))
				.isInstanceOf(InvalidRequestException.class);
	}
}
