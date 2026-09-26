package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what {@link ChatMembership} needs for one case. Every read is brand-matched to the case.
 *
 * <p><strong>Pipeline Sales</strong> = active SALES members holding, unrevoked, the pipeline of the
 * case's deal. A case whose deal the mirror does not hold has none — that is a fact, not an error.
 */
@Component
public class ChatRosterLoader {

	private final ClientAccountRepository accounts;
	private final OpportunityRepository opportunities;
	private final TeamMemberPipelineRepository pipelines;
	private final TeamMemberRepository members;
	private final ExpertCaseOfferRepository offers;

	ChatRosterLoader(ClientAccountRepository accounts, OpportunityRepository opportunities,
			TeamMemberPipelineRepository pipelines, TeamMemberRepository members, ExpertCaseOfferRepository offers) {
		this.accounts = accounts;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.members = members;
		this.offers = offers;
	}

	@Transactional(readOnly = true)
	public CaseRoster load(Case subject) {
		UUID brand = subject.getBrandId();
		UUID client = subject.getContactId() == null ? null
				: accounts.findByBrandIdAndContactId(brand, subject.getContactId()).map(ClientAccount::getId)
						.orElse(null);
		List<UUID> enms = members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand).stream()
				.map(TeamMember::getId).toList();
		UUID expert = Stream.concat(offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.OFFERED).stream(),
				offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.ACCEPTED).stream())
				.map(ExpertCaseOffer::getExpertId).findFirst().orElse(null);
		return new CaseRoster(subject.getId(), brand, client, pipelineSales(subject), subject.getAssignedPm(),
				subject.getAssignedCoordinator(), subject.getAssignedCm(), enms, expert);
	}

	private List<UUID> pipelineSales(Case subject) {
		if (subject.getGhlOpportunityId() == null) {
			return List.of();
		}
		List<UUID> holders = opportunities.findByBrandIdAndGhlId(subject.getBrandId(), subject.getGhlOpportunityId())
				.map(Opportunity::getPipelineId).map(pipelines::membersOn).orElse(List.of());
		if (holders.isEmpty()) {
			return List.of();
		}
		return members.findAllById(holders).stream()
				.filter(TeamMember::isActive)
				.filter((m) -> m.getRole() == Role.SALES)
				.filter((m) -> subject.getBrandId().equals(m.getBrandId()))
				.map(TeamMember::getId).toList();
	}
}
