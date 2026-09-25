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
}
