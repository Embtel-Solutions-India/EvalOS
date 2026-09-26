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

	/** Past this many entries, stale answers are dropped on the next write (review M2). No Redis needed: one instance. */
	static final int PRUNE_ABOVE = 1000;

	private record Answer(boolean online, Instant at) {
	}

	private final Map<String, Answer> recent = new ConcurrentHashMap<>();
	private final ChatRealtime realtime;
	private final java.util.function.Supplier<Instant> clock;

	@org.springframework.beans.factory.annotation.Autowired
	ChatPresence(ChatRealtime realtime) {
		this(realtime, Instant::now);
	}

	ChatPresence(ChatRealtime realtime, java.util.function.Supplier<Instant> clock) {
		this.realtime = realtime;
		this.clock = clock;
	}

	public boolean isOnline(ParticipantKind kind, UUID id) {
		String channel = ChatChannels.personal(kind, id);
		Instant now = clock.get();
		Answer cached = recent.get(channel);
		if (cached != null && cached.at().isAfter(now.minusSeconds(CACHE_SECONDS))) {
			return cached.online();
		}
		boolean online = realtime.isPresent(channel);
		if (recent.size() > PRUNE_ABOVE) {
			Instant stale = now.minusSeconds(CACHE_SECONDS);
			recent.values().removeIf((answer) -> !answer.at().isAfter(stale));
		}
		recent.put(channel, new Answer(online, now));
		return online;
	}

	int cached() {
		return recent.size();
	}
}
