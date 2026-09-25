package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ie.evalos.chat.ParticipantKind;

import org.springframework.stereotype.Component;

/**
 * Whether a person has an app open: present on their own Ably channel (Unit 57 §5–6).
 *
 * <p>Answers are cached ten seconds, so a burst of messages to one conversation asks Ably once per
 * recipient, not once per message.
 */
@Component
public class ChatPresence {

	private static final long CACHE_SECONDS = 10;

	private record Answer(boolean online, Instant at) {
	}

	private final Map<String, Answer> recent = new ConcurrentHashMap<>();
	private final ChatRealtime realtime;

	ChatPresence(ChatRealtime realtime) {
		this.realtime = realtime;
	}

	public boolean isOnline(ParticipantKind kind, UUID id) {
		String channel = ChatChannels.personal(kind, id);
		Answer cached = recent.get(channel);
		if (cached != null && cached.at().isAfter(Instant.now().minusSeconds(CACHE_SECONDS))) {
			return cached.online();
		}
		boolean online = realtime.isPresent(channel);
		recent.put(channel, new Answer(online, Instant.now()));
		return online;
	}
}
