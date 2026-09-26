package com.ie.evalos.chat.live;

import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPresenceTest {

	@Test
	void onlineMeansPresentOnTheirOwnChannel() {
		ChatRealtime realtime = mock(ChatRealtime.class);
		UUID client = UUID.randomUUID();
		when(realtime.isPresent("chat:user:CLIENT:" + client)).thenReturn(true);

		assertThat(new ChatPresence(realtime).isOnline(ParticipantKind.CLIENT, client)).isTrue();
	}

	@Test
	void aRecentAnswerIsReusedForTenSeconds() {
		ChatRealtime realtime = mock(ChatRealtime.class);
		UUID pm = UUID.randomUUID();
		when(realtime.isPresent("chat:user:STAFF:" + pm)).thenReturn(false);
		ChatPresence presence = new ChatPresence(realtime);

		presence.isOnline(ParticipantKind.STAFF, pm);
		presence.isOnline(ParticipantKind.STAFF, pm);

		verify(realtime, times(1)).isPresent("chat:user:STAFF:" + pm);
	}

	/** M2: the cache is pruned of stale answers once it grows, instead of holding every person ever asked about. */
	@Test
	void staleAnswersArePrunedOnceTheCacheGrows() {
		ChatRealtime realtime = mock(ChatRealtime.class);
		java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
				new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-09-26T10:00:00Z"));
		ChatPresence presence = new ChatPresence(realtime, () -> now.get());
		for (int i = 0; i <= ChatPresence.PRUNE_ABOVE; i++) {
			presence.isOnline(ParticipantKind.STAFF, UUID.randomUUID());
		}

		now.set(now.get().plusSeconds(60));
		presence.isOnline(ParticipantKind.STAFF, UUID.randomUUID());

		assertThat(presence.cached()).isEqualTo(1);
	}
}
