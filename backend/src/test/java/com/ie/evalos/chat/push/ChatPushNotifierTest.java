package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationRepository;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPushNotifierTest {

	private final PushSettings settings = new PushSettings("pub", "priv", "mailto:ops@ie.test",
			"http://staff.test", "http://client.test", "http://expert.test");
	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushSender sender = mock(PushSender.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ChatPresence presence = mock(ChatPresence.class);
	private final ChatPushNotifier notifier = new ChatPushNotifier(settings, subscriptions, sender, members,
			conversations, cases, presence, Runnable::run);

	private final UUID brand = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();

	private UUID clientConversation() {
		Conversation conversation = new Conversation(brand, caseId, ConversationType.CLIENT);
		UUID id = UUID.randomUUID();
		when(conversations.findByIdAndBrandId(id, brand)).thenReturn(Optional.of(conversation));
		Case subject = mock(Case.class);
		when(subject.getCaseCode()).thenReturn("IE-1042");
		when(cases.findById(caseId)).thenReturn(Optional.of(subject));
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(brand, id)).thenReturn(List.of(
				new ConversationMember(brand, id, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, id, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
		return id;
	}

	private ChatChanged fromPm(UUID conversation, String text) {
		return new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, new ChatViews.MessageView(
				UUID.randomUUID(), conversation, ParticipantKind.STAFF, pm, "Priya", text, null, 0, Instant.now(), null,
				false, Map.of(), false));
	}

	private PushSubscription subscription(ParticipantKind kind, UUID id) {
		return new PushSubscription(brand, kind, id, "https://push.test/" + UUID.randomUUID(), "p", "a");
	}

	@Test
	void anOfflineClientIsPushedAndTheTextNeverLeaves() {
		UUID conversation = clientConversation();
		when(presence.isOnline(ParticipantKind.CLIENT, client)).thenReturn(false);
		PushSubscription phone = subscription(ParticipantKind.CLIENT, client);
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.CLIENT, client)).thenReturn(List.of(phone));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.SENT);

		notifier.on(fromPm(conversation, "Your passport scan is blurry"));

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(sender).send(eq(phone), payload.capture());
		assertThat(payload.getValue()).contains("Your case team").contains("IE-1042")
				.contains("http://client.test/cases/" + caseId).contains(conversation.toString())
				.doesNotContain("passport");
	}

	@Test
	void anOnlineRecipientAndTheAuthorAreNotPushed() {
		UUID conversation = clientConversation();
		when(presence.isOnline(ParticipantKind.CLIENT, client)).thenReturn(true);

		notifier.on(fromPm(conversation, "hi"));

		verify(subscriptions, never()).findBySubscriberKindAndSubscriberId(ParticipantKind.STAFF, pm);
		verify(sender, never()).send(any(), any());
	}

	@Test
	void aGoneSubscriptionIsDeleted() {
		UUID conversation = clientConversation();
		PushSubscription stale = subscription(ParticipantKind.CLIENT, client);
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.CLIENT, client)).thenReturn(List.of(stale));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.GONE);

		notifier.on(fromPm(conversation, "hi"));

		verify(subscriptions).delete(stale);
	}

	@Test
	void unconfiguredPushDoesNothing() {
		ChatPushNotifier off = new ChatPushNotifier(new PushSettings("", "", "", "a", "b", "c"), subscriptions, sender,
				members, conversations, cases, presence, Runnable::run);

		off.on(fromPm(clientConversation(), "hi"));

		verify(sender, never()).send(any(), any());
	}
}
