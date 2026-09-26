package com.ie.evalos.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRosterLoaderTest {

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);
	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);
	private final TeamMemberPipelineRepository pipelines = mock(TeamMemberPipelineRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final ChatRosterLoader loader = new ChatRosterLoader(accounts, opportunities, pipelines, members, offers);

	private static TeamMember member(UUID id, Role role, UUID brand, boolean active) {
		TeamMember m = mock(TeamMember.class);
		when(m.getId()).thenReturn(id);
		when(m.getRole()).thenReturn(role);
		when(m.getBrandId()).thenReturn(brand);
		when(m.isActive()).thenReturn(active);
		return m;
	}

	@Test
	void salesAreActiveSalesHoldersOfTheDealsPipelineInTheCasesBrandOnly() {
		UUID brand = UUID.randomUUID();
		UUID caseId = UUID.randomUUID();
		UUID pipeline = UUID.randomUUID();
		UUID sales = UUID.randomUUID();
		UUID marketing = UUID.randomUUID();
		UUID inactive = UUID.randomUUID();
		UUID otherBrand = UUID.randomUUID();

		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(caseId);
		when(subject.getBrandId()).thenReturn(brand);
		when(subject.getGhlOpportunityId()).thenReturn("opp_1");
		Opportunity deal = mock(Opportunity.class);
		when(deal.getPipelineId()).thenReturn(pipeline);
		when(opportunities.findByBrandIdAndGhlId(brand, "opp_1")).thenReturn(Optional.of(deal));
		when(pipelines.membersOn(pipeline)).thenReturn(List.of(sales, marketing, inactive, otherBrand));
		// Built before the stubbing below: Mockito refuses a mock being stubbed inside another stub.
		List<TeamMember> held = List.of(member(sales, Role.SALES, brand, true),
				member(marketing, Role.MARKETING, brand, true), member(inactive, Role.SALES, brand, false),
				member(otherBrand, Role.SALES, UUID.randomUUID(), true));
		when(members.findAllById(List.of(sales, marketing, inactive, otherBrand))).thenReturn(held);
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.OFFERED)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.ACCEPTED)).thenReturn(List.of());

		assertThat(loader.load(subject).pipelineSales()).containsExactly(sales);
	}

	@Test
	void theExpertIsTheOneWithAnOpenOrAcceptedOffer() {
		UUID brand = UUID.randomUUID();
		UUID caseId = UUID.randomUUID();
		UUID expert = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(caseId);
		when(subject.getBrandId()).thenReturn(brand);
		ExpertCaseOffer open = mock(ExpertCaseOffer.class);
		when(open.getExpertId()).thenReturn(expert);
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.OFFERED)).thenReturn(List.of(open));
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.ACCEPTED)).thenReturn(List.of());
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());

		assertThat(loader.load(subject).expertId()).isEqualTo(expert);
	}

	@Test
	void theClientIsTheAccountOfTheCasesContact() {
		UUID brand = UUID.randomUUID();
		UUID contact = UUID.randomUUID();
		UUID account = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(UUID.randomUUID());
		when(subject.getBrandId()).thenReturn(brand);
		when(subject.getContactId()).thenReturn(contact);
		ClientAccount held = mock(ClientAccount.class);
		when(held.getId()).thenReturn(account);
		when(accounts.findByBrandIdAndContactId(brand, contact)).thenReturn(Optional.of(held));
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of());

		assertThat(loader.load(subject).clientAccountId()).isEqualTo(account);
	}
}
