package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAccessTest {

	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatAccess access = new ChatAccess(conversations, members);

	private final UUID brand = UUID.randomUUID();

	private Conversation conversation(ConversationType type) {
		return new Conversation(brand, UUID.randomUUID(), type);
	}

	private void isMember(ChatIdentity who, boolean yes) {
		when(members.findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(any(), any(), any(), any()))
				.thenReturn(yes ? Optional.of(mock(ConversationMember.class)) : Optional.empty());
	}

	@Test
	void aCurrentMemberIsAMember() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		isMember(pm, true);
		assertThat(access.level(pm, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void aFormerMemberHasNoAccess() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		isMember(pm, false);
		assertThat(access.level(pm, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.NONE);
	}

	@Test
	void theGmViewsEveryBrand() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		isMember(gm, false);
		assertThat(access.level(gm, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.VIEWER);
	}

	@Test
	void aBrandManagerViewsOnlyTheirBrand() {
		ChatIdentity own = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.BRAND_MANAGER);
		ChatIdentity other = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), UUID.randomUUID(),
				Role.BRAND_MANAGER);
		isMember(own, false);
		assertThat(access.level(own, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.VIEWER);
		assertThat(access.level(other, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.NONE);
	}

	@Test
	void aClientCanNeverReachInternalOrExpertEvenIfARowSaysSo() {
		ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);
		isMember(client, true);
		assertThat(access.level(client, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(client, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(client, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void anExpertCanOnlyReachExpert() {
		ChatIdentity expert = ChatIdentity.expert(UUID.randomUUID(), brand);
		isMember(expert, true);
		assertThat(access.level(expert, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(expert, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void writingToAReadOnlyConversationIsRefusedAsReadOnly() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		Conversation closed = conversation(ConversationType.INTERNAL);
		closed.makeReadOnly(java.time.Instant.now());
		when(conversations.findByIdAndBrandId(any(), any())).thenReturn(Optional.of(closed));
		isMember(pm, true);

		assertThatThrownBy(() -> access.requireWrite(pm, UUID.randomUUID()))
				.isInstanceOf(ConversationReadOnlyException.class);
	}

	@Test
	void aViewerCannotWrite() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		when(conversations.findById(any())).thenReturn(Optional.of(conversation(ConversationType.CLIENT)));
		isMember(gm, false);

		assertThatThrownBy(() -> access.requireWrite(gm, UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void anUnknownConversationIsTheSame403AsAForeignOne() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		when(conversations.findByIdAndBrandId(any(), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> access.requireRead(pm, UUID.randomUUID()))
				.isInstanceOf(ForbiddenException.class).hasMessage(ChatAccess.NOT_YOURS);
	}
}
