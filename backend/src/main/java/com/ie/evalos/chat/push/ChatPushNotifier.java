package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationRepository;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.CaseRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A browser push for every member who is not connected when a message lands (Unit 57 §6, D37).
 *
 * <p><strong>Who and where, never what.</strong> A lock screen is not private, and a client's case
 * correspondence is. The push names the sender and the case; the text is read in the app.
 */
@Component
public class ChatPushNotifier {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final PushSettings settings;
	private final PushSubscriptionRepository subscriptions;
	private final PushSender sender;
	private final ConversationMemberRepository members;
	private final ConversationRepository conversations;
	private final CaseRepository cases;
	private final ChatPresence presence;
	private final Executor executor;

	ChatPushNotifier(PushSettings settings, PushSubscriptionRepository subscriptions, PushSender sender,
			ConversationMemberRepository members, ConversationRepository conversations, CaseRepository cases,
			ChatPresence presence,
			@org.springframework.beans.factory.annotation.Qualifier("chatPushExecutor") Executor executor) {
		this.settings = settings;
		this.subscriptions = subscriptions;
		this.sender = sender;
		this.members = members;
		this.conversations = conversations;
		this.cases = cases;
		this.presence = presence;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(ChatChanged change) {
		if (!settings.enabled() || change.kind() != ChatChanged.Kind.MESSAGE_CREATED) {
			return;
		}
		// Presence checks are Ably calls and sends are HTTPS: none of it on the request thread (review I6).
		executor.execute(() -> notifyOffline(change));
	}

	private void notifyOffline(ChatChanged change) {
		ChatViews.MessageView message = (ChatViews.MessageView) change.payload();
		Conversation conversation = conversations.findByIdAndBrandId(change.conversationId(), change.brandId()).orElse(null);
		if (conversation == null) {
			return;
		}
		String caseCode = cases.findById(conversation.getCaseId()).map(Case::getCaseCode).orElse("your case");
		List<ConversationMember> offline = members
				.findByBrandIdAndConversationIdAndLeftAtIsNull(change.brandId(), change.conversationId()).stream()
				.filter((m) -> !(m.getKind() == message.authorKind() && m.getMemberId().equals(message.authorId())))
				.filter((m) -> !presence.isOnline(m.getKind(), m.getMemberId()))
				.toList();
		for (ConversationMember recipient : offline) {
			String payload = payload(recipient, message, conversation, change.conversationId(), caseCode);
			for (PushSubscription to : subscriptions.findBySubscriberKindAndSubscriberId(recipient.getKind(),
					recipient.getMemberId())) {
				deliver(to, payload);
			}
		}
	}

	private void deliver(PushSubscription to, String payload) {
		switch (sender.send(to, payload)) {
			case SENT -> {
				to.markSent(Instant.now());
				subscriptions.save(to);
			}
			case GONE -> subscriptions.delete(to);
			case FAILED -> {
				// Logged by the sender; kept, because a push service having a bad minute is not a dead browser.
			}
		}
	}

	private String payload(ConversationMember recipient, ChatViews.MessageView message, Conversation conversation,
			java.util.UUID conversationId, String caseCode) {
		String sender = recipient.getKind() == ParticipantKind.CLIENT && message.authorKind() == ParticipantKind.STAFF
				? "Your case team" : message.authorName();
		String kind = switch (conversation.getType()) {
			case CLIENT -> "Client";
			case INTERNAL -> "Internal";
			case EXPERT -> "Expert";
		};
		String url = switch (recipient.getKind()) {
			// The conversation, not the case screen: Sales take part in chat but read no case (D19c).
			case STAFF -> settings.staffOrigin() + "/conversations/" + conversationId;
			case CLIENT -> settings.clientBase() + "/cases/" + conversation.getCaseId();
			case EXPERT -> settings.expertBase() + "/case";
		};
		try {
			return JSON.writeValueAsString(Map.of("title", "New message from " + sender,
					"body", caseCode + " · " + kind + " conversation", "url", url, "tag", conversationId.toString()));
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
