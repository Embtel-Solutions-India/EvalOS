package com.ie.evalos.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 30 messages a minute per identity (Unit 57 §8).
 *
 * <p>ponytail: in memory, so it holds for one backend instance — the deployment today. Several
 * instances would need the count in Postgres or Redis.
 */
@Component
public class ChatRateLimiter {

	static final int PER_MINUTE = 30;

	private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
	private final Clock clock;

	ChatRateLimiter() {
		this(Clock.systemUTC());
	}

	ChatRateLimiter(Clock clock) {
		this.clock = clock;
	}

	public void check(ChatIdentity who) {
		Instant now = clock.instant();
		Deque<Instant> window = recent.computeIfAbsent(who.kind() + ":" + who.id(), (key) -> new ArrayDeque<>());
		synchronized (window) {
			while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
				window.pollFirst();
			}
			if (window.size() >= PER_MINUTE) {
				throw new TooManyMessagesException();
			}
			window.addLast(now);
		}
	}

	public static class TooManyMessagesException extends RuntimeException {
		public TooManyMessagesException() {
			super("That is a lot of messages in a minute. Wait a moment and send again.");
		}
	}
}
