package com.ie.evalos.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The dashboards' owner names: only the cases' own brands, only active members. */
class TeamMemberQueryServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();

	private final TeamMemberRepository repository = mock(TeamMemberRepository.class);
	private final TeamMemberQueryService service = new TeamMemberQueryService(repository);

	private static TeamMember member(UUID brand, String name, boolean active) {
		TeamMember member = new TeamMember("x@ie.test", "hash");
		ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
		member.describe(name, "x@ie.test", com.ie.evalos.domain.Role.CASE_MANAGER, brand, null);
		member.setActive(active);
		return member;
	}

	@Test
	void namesStayInsideTheCasesBrandsAndSkipInactiveMembers() {
		TeamMember chris = member(BRAND, "Chris", true);
		TeamMember elsewhere = member(OTHER_BRAND, "Other brand", true);
		TeamMember gone = member(BRAND, "Gone", false);
		Set<UUID> ids = Set.of(chris.getId(), elsewhere.getId(), gone.getId());
		given(repository.findAllById(ids)).willReturn(List.of(chris, elsewhere, gone));

		Map<UUID, String> names = service.namesOf(ids, Set.of(BRAND));

		assertThat(names).containsOnly(Map.entry(chris.getId(), "Chris"));
	}

	@Test
	void noIdsReadsNothing() {
		assertThat(service.namesOf(Set.of(), Set.of(BRAND))).isEmpty();
	}
}
