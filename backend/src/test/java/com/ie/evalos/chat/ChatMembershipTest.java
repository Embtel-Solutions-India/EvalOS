package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMembershipTest {

	private static final UUID CLIENT = UUID.randomUUID();
	private static final UUID SALES = UUID.randomUUID();
	private static final UUID PM = UUID.randomUUID();
	private static final UUID PC = UUID.randomUUID();
	private static final UUID CM = UUID.randomUUID();
	private static final UUID ENM = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private static CaseRoster full() {
		return new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), CLIENT, List.of(SALES), PM, PC, CM,
				List.of(ENM), EXPERT);
	}

	@Test
	void clientConversationIsClientSalesAndTheCaseTeam() {
		assertThat(ChatMembership.expected(full(), ConversationType.CLIENT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.CLIENT, CLIENT, ChatRole.CLIENT),
				new ExpectedMember(ParticipantKind.STAFF, SALES, ChatRole.SALES),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER));
	}

	@Test
	void internalConversationIsSalesTheCaseTeamAndEnmsButNeverClientOrExpert() {
		assertThat(ChatMembership.expected(full(), ConversationType.INTERNAL)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.STAFF, SALES, ChatRole.SALES),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER),
				new ExpectedMember(ParticipantKind.STAFF, ENM, ChatRole.ENM));
	}

	@Test
	void expertConversationIsTheCaseTeamEnmsAndTheExpertButNeverClientOrSales() {
		assertThat(ChatMembership.expected(full(), ConversationType.EXPERT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER),
				new ExpectedMember(ParticipantKind.STAFF, ENM, ChatRole.ENM),
				new ExpectedMember(ParticipantKind.EXPERT, EXPERT, ChatRole.EXPERT));
	}

	@Test
	void noMirroredOpportunityMeansNoSales() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), CLIENT, List.of(), PM, null,
				null, List.of(), null);

		assertThat(ChatMembership.expected(roster, ConversationType.CLIENT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.CLIENT, CLIENT, ChatRole.CLIENT),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM));
	}

	@Test
	void aClientWithNoAccountYetIsSimplyAbsent() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), null, List.of(), PM, null,
				null, List.of(), null);

		assertThat(ChatMembership.expected(roster, ConversationType.CLIENT))
				.containsExactly(new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM));
	}

	@Test
	void onePersonHoldingTwoRolesIsOneMember() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), null, List.of(CM), null, null,
				CM, List.of(CM), null);

		assertThat(ChatMembership.expected(roster, ConversationType.INTERNAL))
				.containsExactly(new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER));
	}
}
