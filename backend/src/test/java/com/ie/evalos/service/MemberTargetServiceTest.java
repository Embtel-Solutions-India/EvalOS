package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The rules of a member's monthly target (D75). The newest-row and brand-scoping rules are SQL, so {@code MemberTargetServiceDbTest} proves those. */
class MemberTargetServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final MemberTargetService service = new MemberTargetService(jdbc, members);
	private final UUID setBy = UUID.randomUUID();

	private TeamMember member(Role role) {
		TeamMember member = new TeamMember() {
		};
		ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(member, "role", role);
		ReflectionTestUtils.setField(member, "brandId", BRAND);
		given(members.findById(member.getId())).willReturn(Optional.of(member));
		return member;
	}

	@Test
	void theRoleDecidesWhatATargetCounts() {
		assertThat(MemberTargetService.kindOf(Role.SALES)).contains(MemberTargetService.TargetKind.WON_VALUE);
		assertThat(MemberTargetService.kindOf(Role.MARKETING)).contains(MemberTargetService.TargetKind.LEADS);
		assertThat(MemberTargetService.kindOf(Role.PROJECT_MANAGER)).isEmpty();
	}

	@Test
	void aSalesTargetIsStoredAgainstTheMembersBrandOnTheFirstOfTheMonth() {
		TeamMember sales = member(Role.SALES);

		service.set(sales.getId(), LocalDate.of(2026, 10, 17), new BigDecimal("1500"), setBy);

		verify(jdbc).update(contains("INSERT INTO member_monthly_target"), eq(BRAND), eq(sales.getId()), eq(OCT),
				eq("WON_VALUE"), eq(new BigDecimal("1500")), eq(setBy));
	}

	@Test
	void aMarketingTargetIsALeadsCount() {
		TeamMember marketing = member(Role.MARKETING);

		service.set(marketing.getId(), OCT, new BigDecimal("40"), setBy);

		verify(jdbc).update(anyString(), eq(BRAND), eq(marketing.getId()), eq(OCT), eq("LEADS"),
				eq(new BigDecimal("40")), eq(setBy));
	}

	@Test
	void aMemberWhoIsNotSalesOrMarketingCannotBeGivenATarget() {
		TeamMember pm = member(Role.PROJECT_MANAGER);

		assertThatThrownBy(() -> service.set(pm.getId(), OCT, new BigDecimal("5"), setBy))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("Sales and Marketing");
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void aLeadsTargetMustBeAWholeNumber() {
		TeamMember marketing = member(Role.MARKETING);

		assertThatThrownBy(() -> service.set(marketing.getId(), OCT, new BigDecimal("2.5"), setBy))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("whole number");
		service.set(marketing.getId(), OCT, new BigDecimal("2.0"), setBy);
	}

	@Test
	void noTargetIsNegative() {
		TeamMember sales = member(Role.SALES);

		assertThatThrownBy(() -> service.set(sales.getId(), OCT, new BigDecimal("-1"), setBy))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void anUnknownMemberIsRefused() {
		given(members.findById(any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.set(UUID.randomUUID(), OCT, BigDecimal.TEN, setBy))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static GmOverviewService.DeskRow desk(UUID id, String name, Role role, int newLeads, String wonValue) {
		return new GmOverviewService.DeskRow(id, name, role, newLeads, 3, new BigDecimal(wonValue), 2, BigDecimal.TEN);
	}

	@Test
	void aSalesRowShowsWonValueAgainstItsTargetAndAMarketingRowShowsLeads() {
		UUID salesId = UUID.randomUUID();
		UUID marketingId = UUID.randomUUID();
		given(jdbc.query(contains("member_monthly_target"), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<BigDecimal>>any(),
				eq(BRAND), eq(salesId), eq(OCT))).willReturn(List.of(new BigDecimal("1000.00")));

		List<MemberTargetService.TargetRow> rows = service.overview(BRAND, OCT, List.of(
				desk(salesId, "Sam", Role.SALES, 9, "900"), desk(marketingId, "Mia", Role.MARKETING, 7, "0")));

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).kind()).isEqualTo(MemberTargetService.TargetKind.WON_VALUE);
		assertThat(rows.get(0).target()).isEqualByComparingTo("1000");
		assertThat(rows.get(0).progress()).isEqualByComparingTo("900");
		assertThat(rows.get(1).kind()).isEqualTo(MemberTargetService.TargetKind.LEADS);
		assertThat(rows.get(1).target()).as("a member with no row is not set, never 0").isNull();
		assertThat(rows.get(1).progress()).isEqualByComparingTo("7");
	}

	@Test
	void aDeskWhoseRoleHasNoTargetKindIsLeftOut() {
		List<MemberTargetService.TargetRow> rows = service.overview(BRAND, OCT,
				List.of(desk(UUID.randomUUID(), "Pat", Role.PROJECT_MANAGER, 0, "0")));

		assertThat(rows).isEmpty();
	}
}
