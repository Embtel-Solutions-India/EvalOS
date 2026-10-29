package com.ie.evalos.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** 2026-10-30: the GM opens one production member's dashboard; nothing else may view as anyone. */
class JwtFilterViewAsTest {

	private static final UUID BRAND = UUID.randomUUID();
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final TeamMemberPipelineRepository pipelines = mock(TeamMemberPipelineRepository.class);

	private static StaffPrincipal as(Role role) {
		return new StaffPrincipal(UUID.randomUUID(), "x@ie.test", "X", role, role == Role.GM ? null : BRAND, null,
				null, true);
	}

	private UUID member(Role role, boolean active) {
		TeamMember member = new TeamMember(role + "@ie.test", "hash");
		UUID id = UUID.randomUUID();
		ReflectionTestUtils.setField(member, "id", id);
		member.describe("Member", role + "@ie.test", role, BRAND, null);
		member.setActive(active);
		given(members.findById(id)).willReturn(Optional.of(member));
		given(pipelines.ghlIdsFor(id)).willReturn(List.of("pipe_hire"));
		return id;
	}

	private static MockHttpServletRequest get(String path) {
		return new MockHttpServletRequest("GET", path);
	}

	@Test
	void theGmViewsAProductionMembersDashboardAsThem() {
		UUID enm = member(Role.EXPERT_NETWORK_MANAGER, true);

		StaffPrincipal viewed = JwtFilter.viewedAs(get("/api/metrics/expert-network"), as(Role.GM), enm.toString(),
				members, pipelines);

		assertThat(viewed.memberId()).isEqualTo(enm);
		assertThat(viewed.role()).isEqualTo(Role.EXPERT_NETWORK_MANAGER);
		assertThat(viewed.ghlPipelineIds()).containsExactly("pipe_hire");
	}

	@Test
	void nobodyButTheGmMayViewAs() {
		UUID cm = member(Role.CASE_MANAGER, true);

		assertThat(JwtFilter.viewedAs(get("/api/metrics/case-manager"), as(Role.PROJECT_MANAGER), cm.toString(),
				members, pipelines)).isNull();
	}

	@Test
	void onlyTheDashboardReadsAndOnlyGet() {
		UUID cm = member(Role.CASE_MANAGER, true);

		assertThat(JwtFilter.viewedAs(get("/api/cases"), as(Role.GM), cm.toString(), members, pipelines)).isNull();
		assertThat(JwtFilter.viewedAs(new MockHttpServletRequest("POST", "/api/metrics/case-manager"), as(Role.GM),
				cm.toString(), members, pipelines)).isNull();
	}

	@Test
	void notSalesNotAnAdminNotSomeoneWhoLeft() {
		UUID sales = member(Role.SALES, true);
		UUID left = member(Role.CASE_MANAGER, false);

		assertThat(JwtFilter.viewedAs(get("/api/metrics/pm"), as(Role.GM), sales.toString(), members, pipelines)).isNull();
		assertThat(JwtFilter.viewedAs(get("/api/metrics/pm"), as(Role.GM), left.toString(), members, pipelines)).isNull();
		assertThat(JwtFilter.viewedAs(get("/api/metrics/pm"), as(Role.GM), "not-a-uuid", members, pipelines)).isNull();
	}
}
