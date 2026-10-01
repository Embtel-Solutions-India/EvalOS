package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit 67: the case page names its team, and only from the case's own brand. */
class CaseDetailServiceTeamTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final CaseDetailService service = new CaseDetailService(lifecycle, mock(ContactSnapshotRepository.class),
			mock(ExpertRepository.class), mock(DocumentChecklistItemRepository.class),
			mock(OpportunityNoteRepository.class), members);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theTeamIsNamedFromTheCasesOwnBrand() {
		StaffPrincipal pm = new StaffPrincipal(UUID.randomUUID(), "pm@ie.test", "PM", Role.PROJECT_MANAGER, BRAND,
				null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(pm, null, pm.getAuthorities()));

		Case subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
		UUID pmId = UUID.randomUUID();
		UUID cmId = UUID.randomUUID();
		UUID strayId = UUID.randomUUID();
		subject.setAssignedPm(pmId);
		subject.setAssignedCm(cmId);
		subject.setAssignedCoordinator(strayId);
		when(lifecycle.read(org.mockito.ArgumentMatchers.any())).thenReturn(subject);
		List<TeamMember> seats = List.of(member(pmId, BRAND, "Priya PM"), member(cmId, BRAND, "Chris CM"),
				member(strayId, UUID.randomUUID(), "Someone Elsewhere"));
		when(members.findAllById(anyIterable())).thenReturn(seats);

		CaseDetailService.Team team = service.detail(UUID.randomUUID()).team();

		assertThat(team).isEqualTo(new CaseDetailService.Team("Priya PM", "Chris CM", null));
	}

	private static TeamMember member(UUID id, UUID brandId, String name) {
		TeamMember member = mock(TeamMember.class);
		when(member.getId()).thenReturn(id);
		when(member.getBrandId()).thenReturn(brandId);
		when(member.getDisplayName()).thenReturn(name);
		return member;
	}
}
