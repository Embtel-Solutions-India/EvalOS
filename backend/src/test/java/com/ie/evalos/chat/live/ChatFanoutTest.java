package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ExpectedMember;
import com.ie.evalos.chat.MembersChanged;
import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatFanoutTest {

	private final ChatRealtime realtime = mock(ChatRealtime.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatFanout fanout = new ChatFanout(realtime, members, Runnable::run);

	private final UUID brand = UUID.randomUUID();
	private final UUID conversation = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();

	private ChatViews.MessageView fromPm() {
		return new ChatViews.MessageView(UUID.randomUUID(), conversation, ParticipantKind.STAFF, pm, "Priya", "hi",
				null, 0, Instant.now(), null, false, Map.of(), false);
	}

	private void twoMembers() {
		when(realtime.enabled()).thenReturn(true);
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(brand, conversation)).thenReturn(List.of(
				new ConversationMember(brand, conversation, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, conversation, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
	}

	@Test
	void aNewMessageGoesToEachMembersOwnChannelAndTheViewChannel() {
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, fromPm()));

		verify(realtime).publish(eq("chat:user:STAFF:" + pm), eq("message.created"), any(ChatEnvelope.class));
		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("message.created"), any(ChatEnvelope.class));
		verify(realtime).publish(eq("chat:view:" + brand + ":" + conversation), eq("message.created"),
				any(ChatEnvelope.class));
	}

	@Test
	void theAuthorGetsNoUnreadBumpButEveryoneElseDoes() {
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, fromPm()));

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("unread.changed"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + pm), eq("unread.changed"), any());
	}

	@Test
	void aRemovedMemberIsToldAccessIsRevokedAndGetsNothingElse() {
		UUID oldCm = UUID.randomUUID();
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MEMBERS_CHANGED, new MembersChanged(List.of(),
				List.of(new ExpectedMember(ParticipantKind.STAFF, oldCm, ChatRole.CASE_MANAGER)))));

		verify(realtime).publish(eq("chat:user:STAFF:" + oldCm), eq("access.revoked"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + oldCm), eq("members.changed"), any());
		verify(realtime).publish(eq("chat:user:STAFF:" + pm), eq("members.changed"), any());
	}

	@Test
	void theEnvelopeCarriesTheConversationAndTheView() {
		twoMembers();
		ChatViews.MessageView message = fromPm();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, message));

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("message.created"),
				argThat((ChatEnvelope e) -> e.conversationId().equals(conversation) && e.data() == message));
	}

	/** Review I6: the Ably calls leave the request thread — nothing is published until the executor runs. */
	@Test
	void publishingHappensOnTheExecutorNotTheCallersThread() {
		twoMembers();
		java.util.List<Runnable> queued = new java.util.ArrayList<>();
		ChatFanout deferred = new ChatFanout(realtime, members, queued::add);

		deferred.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, fromPm()));

		verify(realtime, never()).publish(any(), any(), any());
		queued.forEach(Runnable::run);
		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("message.created"), any(ChatEnvelope.class));
	}
}
