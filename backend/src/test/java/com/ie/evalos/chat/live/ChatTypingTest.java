package com.ie.evalos.chat.live;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.chat.ChatAccess;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTypingTest {

	private final ChatRealtime realtime = mock(ChatRealtime.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatAccess access = mock(ChatAccess.class);
	private final ChatTyping typing = new ChatTyping(realtime, members, access);

	private final UUID brand = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();
	private final ChatIdentity who = new ChatIdentity(ParticipantKind.STAFF, pm, brand, Role.PROJECT_MANAGER);

	private UUID conversation() {
		Conversation c = new Conversation(brand, UUID.randomUUID(), ConversationType.CLIENT);
		UUID id = UUID.randomUUID();
		when(access.requireWrite(who, id)).thenReturn(c);
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(eq(brand), any())).thenReturn(List.of(
				new ConversationMember(brand, id, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, id, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
		return id;
	}

	@Test
	void typingReachesTheOtherMembersButNotTheTypist() {
		typing.typing(who, conversation());

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("typing"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + pm), eq("typing"), any());
	}

	@Test
	void typingIsThrottledToOncePerThreeSeconds() {
		UUID id = conversation();

		typing.typing(who, id);
		typing.typing(who, id);

		verify(realtime, times(1)).publish(eq("chat:user:CLIENT:" + client), eq("typing"), any());
	}

	@Test
	void aViewerOrStrangerCannotSignalTyping() {
		UUID id = UUID.randomUUID();
		when(access.requireWrite(who, id)).thenThrow(new ForbiddenException(ChatAccess.NOT_YOURS));

		assertThatThrownBy(() -> typing.typing(who, id)).isInstanceOf(ForbiddenException.class);
		verify(realtime, never()).publish(any(), any(), any());
	}
}
