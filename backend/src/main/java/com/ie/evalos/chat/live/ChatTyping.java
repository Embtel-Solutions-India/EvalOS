package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ie.evalos.chat.ChatAccess;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMemberRepository;

import org.springframework.stereotype.Component;

/**
 * "Somebody is typing", relayed by the backend because no token may publish (Unit 57 §5).
 * Throttled to one per three seconds per person per conversation — every Ably message is billed.
 * Never stored.
 */
@Component
public class ChatTyping {

	private static final long EVERY_SECONDS = 3;

	private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
	private final ChatRealtime realtime;
	private final ConversationMemberRepository members;
	private final ChatAccess access;

	ChatTyping(ChatRealtime realtime, ConversationMemberRepository members, ChatAccess access) {
		this.realtime = realtime;
		this.members = members;
		this.access = access;
	}

	public void typing(ChatIdentity who, UUID conversationId) {
		Conversation conversation = access.requireWrite(who, conversationId);
		String key = ChatChannels.clientId(who) + "@" + conversationId;
		Instant now = Instant.now();
		Instant previous = lastSent.get(key);
		if (previous != null && previous.isAfter(now.minusSeconds(EVERY_SECONDS))) {
			return;
		}
		lastSent.put(key, now);
		ChatEnvelope envelope = new ChatEnvelope("typing", conversationId,
				Map.of("kind", who.kind(), "id", who.id()));
		members.findByBrandIdAndConversationIdAndLeftAtIsNull(conversation.getBrandId(), conversationId).stream()
				.filter((m) -> !(m.getKind() == who.kind() && m.getMemberId().equals(who.id())))
				.forEach((m) -> realtime.publish(ChatChannels.personal(m.getKind(), m.getMemberId()), "typing", envelope));
	}
}
