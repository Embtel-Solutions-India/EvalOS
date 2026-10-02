package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Segment;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit 68: the GM's staff writes — validated before the CHECKs, audited without the password. */
class TeamMemberAdminServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID GM_ID = UUID.randomUUID();

	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final TeamMemberPipelineRepository grants = mock(TeamMemberPipelineRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final TeamMemberAdminService service = new TeamMemberAdminService(members, grants, brands,
			new BCryptPasswordEncoder(4), audit);

	@BeforeEach
	void asTheGm() {
		StaffPrincipal gm = new StaffPrincipal(GM_ID, "gm@ie.test", "GM", Role.GM, null, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(gm, null, gm.getAuthorities()));
		when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(members.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
		when(members.findByGhlUserId(any())).thenReturn(Optional.empty());
		when(brands.findById(BRAND)).thenReturn(Optional.of(mock(Brand.class)));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private static TeamMemberAdminService.Details details(Role role, UUID brand, Segment segment) {
		return new TeamMemberAdminService.Details("  Nia Desk ", " Nia@IE.test ", role, brand, segment, " ");
	}

	@Test
	void aMemberIsCreatedCleanAndTheTrailHoldsNoPassword() {
		TeamMember created = service.create(details(Role.SALES, BRAND, Segment.ATTORNEY), "a-long-enough-password");

		assertThat(created.getEmail()).isEqualTo("nia@ie.test");
		assertThat(created.getDisplayName()).isEqualTo("Nia Desk");
		assertThat(created.getGhlUserId()).isNull();
		assertThat(new BCryptPasswordEncoder().matches("a-long-enough-password", created.getPasswordHash())).isTrue();
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("TEAM_MEMBER"), any(), eq(AuditAction.CREATED), eq(GM_ID), isNull(), after.capture());
		assertThat(after.getValue().toString()).doesNotContain("password").doesNotContain(created.getPasswordHash());
	}

	@Test
	void theRoleRulesAreA400BeforeTheDatabaseSeesThem() {
		assertThatThrownBy(() -> service.create(details(Role.CASE_MANAGER, null, null), "a-long-enough-password"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("brand");
		assertThatThrownBy(() -> service.create(details(Role.SALES, BRAND, null), "a-long-enough-password"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("segment");
		assertThatThrownBy(() -> service.create(details(Role.CASE_MANAGER, BRAND, null), "short"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("12");
		// A GM is cross-brand, and a non-desk carries no segment, whatever was sent.
		TeamMember gm = service.create(details(Role.GM, BRAND, Segment.ATTORNEY), "a-long-enough-password");
		assertThat(gm.getBrandId()).isNull();
		assertThat(gm.getSegment()).isNull();
	}

	@Test
	void aTakenEmailIsRefused() {
		when(members.findByEmailIgnoreCase("nia@ie.test")).thenReturn(Optional.of(mock(TeamMember.class)));

		assertThatThrownBy(() -> service.create(details(Role.CASE_MANAGER, BRAND, null), "a-long-enough-password"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("already");
	}

	@Test
	void theGmCannotLockThemselvesOut() {
		TeamMember me = mock(TeamMember.class);
		when(me.getId()).thenReturn(GM_ID);
		when(me.getRole()).thenReturn(Role.GM);
		when(members.findById(GM_ID)).thenReturn(Optional.of(me));

		assertThatThrownBy(() -> service.setActive(GM_ID, false)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.update(GM_ID, details(Role.BRAND_MANAGER, BRAND, null)))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("own role");
		verify(members, never()).save(me);
	}

	@Test
	void aDeskWithPipelinesKeepsItsRoleUntilTheyAreRevoked() {
		UUID id = UUID.randomUUID();
		TeamMember desk = mock(TeamMember.class);
		when(desk.getId()).thenReturn(id);
		when(desk.getRole()).thenReturn(Role.SALES);
		when(desk.getBrandId()).thenReturn(BRAND);
		when(members.findById(id)).thenReturn(Optional.of(desk));
		when(grants.ghlIdsFor(id)).thenReturn(List.of("pipe_1"));

		assertThatThrownBy(() -> service.update(id, details(Role.CASE_MANAGER, BRAND, null)))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("pipelines");
	}
}
