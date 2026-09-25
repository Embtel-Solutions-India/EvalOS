package com.ie.evalos.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatRosterLoader roster = mock(ChatRosterLoader.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ConversationService service =
			new ConversationService(conversations, members, roster, offers, audit, events);

	private final UUID brand = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();

	private Case subject(Stage stage) {
		Case c = mock(Case.class);
		when(c.getId()).thenReturn(caseId);
		when(c.getBrandId()).thenReturn(brand);
		when(c.getCurrentStage()).thenReturn(stage);
		return c;
	}

	@Test
	void aNewCaseGetsThreeConversationsAndItsMembers() {
		Case c = subject(Stage.DOC_COLLECTION);
		when(conversations.findByBrandIdAndCaseIdAndType(any(), any(), any())).thenReturn(Optional.empty());
		when(conversations.saveAndFlush(any(Conversation.class))).thenAnswer((call) -> call.getArgument(0));
		when(roster.load(c)).thenReturn(new CaseRoster(caseId, brand, null, List.of(), pm, null, null, List.of(), null));
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(any(), any())).thenReturn(List.of());
		when(members.addIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

		List<Conversation> made = service.ensureAndSync(c);

		assertThat(made).extracting(Conversation::getType)
				.containsExactlyInAnyOrder(ConversationType.CLIENT, ConversationType.INTERNAL, ConversationType.EXPERT);
		verify(members, times(3)).addIfAbsent(eq(brand), any(), eq("STAFF"), eq(pm), eq("PM"));
		verify(audit, times(3)).recordSystemEvent(eq(brand), eq("CONVERSATION"), any(), eq(AuditAction.CHAT_MEMBER_ADDED),
				any(), any());
	}

	@Test
	void aReassignedCaseManagerLeavesAndTheNewOneJoinsWithHistoryKept() {
		Case c = subject(Stage.DRAFT_IN_PROGRESS);
		UUID oldCm = UUID.randomUUID();
		UUID newCm = UUID.randomUUID();
		Conversation internal = new Conversation(brand, caseId, ConversationType.INTERNAL);
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.INTERNAL))
				.thenReturn(Optional.of(internal));
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.CLIENT)).thenReturn(Optional.of(
				new Conversation(brand, caseId, ConversationType.CLIENT)));
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.EXPERT)).thenReturn(Optional.of(
				new Conversation(brand, caseId, ConversationType.EXPERT)));
		when(roster.load(c)).thenReturn(new CaseRoster(caseId, brand, null, List.of(), null, null, newCm, List.of(), null));
		ConversationMember leaving = new ConversationMember(brand, internal.getId(), ParticipantKind.STAFF, oldCm,
				ChatRole.CASE_MANAGER);
		// Unsaved conversations have no id, so answer by call order: CLIENT, INTERNAL, EXPERT
		// (ConversationType.values()). The old Case Manager is held only in INTERNAL.
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(eq(brand), any()))
				.thenReturn(List.of(), List.of(leaving), List.of());
		when(members.addIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

		service.ensureAndSync(c);

		assertThat(leaving.isCurrent()).isFalse();
		assertThat(leaving.getLeftReason()).isEqualTo(LeftReason.REASSIGNED);
		org.mockito.ArgumentCaptor<Object> published = org.mockito.ArgumentCaptor.forClass(Object.class);
		verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(published.capture());
		MembersChanged internalDiff = published.getAllValues().stream()
				.filter((e) -> e instanceof ChatChanged change && change.kind() == ChatChanged.Kind.MEMBERS_CHANGED
						&& change.payload() instanceof MembersChanged m && !m.removed().isEmpty())
				.map((e) -> (MembersChanged) ((ChatChanged) e).payload()).findFirst().orElseThrow();
		assertThat(internalDiff.removed()).containsExactly(
				new ExpectedMember(ParticipantKind.STAFF, oldCm, ChatRole.CASE_MANAGER));
		assertThat(internalDiff.added()).containsExactly(
				new ExpectedMember(ParticipantKind.STAFF, newCm, ChatRole.CASE_MANAGER));
		verify(members, never()).delete(any());
		verify(members, times(3)).addIfAbsent(eq(brand), any(), eq("STAFF"), eq(newCm), eq("CASE_MANAGER"));
	}

	@Test
	void aClosedCaseMakesAllThreeReadOnlyOnce() {
		Case c = subject(Stage.CLOSED);
		Conversation one = new Conversation(brand, caseId, ConversationType.CLIENT);
		when(conversations.findByBrandIdAndCaseId(brand, caseId)).thenReturn(List.of(one));

		service.makeReadOnly(c);
		service.makeReadOnly(c);

		assertThat(one.isReadOnly()).isTrue();
		verify(audit, times(1)).recordSystemEvent(eq(brand), eq("CONVERSATION"), any(), eq(AuditAction.CHAT_READ_ONLY),
				any(), any());
	}
}
