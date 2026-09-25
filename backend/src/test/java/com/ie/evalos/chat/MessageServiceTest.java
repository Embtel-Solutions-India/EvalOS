package com.ie.evalos.chat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rules of writing to a conversation (Unit 57 §0 #7, §8). */
class MessageServiceTest {

	private final ChatAccess access = mock(ChatAccess.class);
	private final MessageRepository messages = mock(MessageRepository.class);
	private final MessageReactionRepository reactions = mock(MessageReactionRepository.class);
	private final MessageReadRepository reads = mock(MessageReadRepository.class);
	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ChatInboxQuery query = mock(ChatInboxQuery.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ChatRateLimiter limiter =
			new ChatRateLimiter(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC));
	private final MessageService service = new MessageService(access, messages, reactions, reads, conversations,
			query, audit, events, limiter);

	private final UUID brand = UUID.randomUUID();
	private final UUID conversationId = UUID.randomUUID();
	private final Conversation conversation = new Conversation(brand, UUID.randomUUID(), ConversationType.CLIENT);
	private final ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
	private final ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);

	@BeforeEach
	void aWritableConversation() {
		ReflectionTestUtils.setField(conversation, "id", conversationId);
		when(access.requireWrite(any(), eq(conversationId))).thenReturn(conversation);
		when(access.requireRead(any(), eq(conversationId))).thenReturn(conversation);
		when(access.level(any(), eq(conversation))).thenReturn(ChatAccessLevel.MEMBER);
		when(messages.save(any(Message.class))).thenAnswer((call) -> {
			Message saved = call.getArgument(0);
			if (saved.getId() == null) {
				ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
				ReflectionTestUtils.setField(saved, "createdAt", Instant.parse("2026-09-26T10:00:00Z"));
			}
			return saved;
		});
		when(query.names(any())).thenReturn(Map.of());
	}

	private Message existing(ChatIdentity author, String body, UUID parent) {
		Message m = new Message(brand, conversationId, author.kind(), author.id(), body, parent);
		ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-09-26T09:00:00Z"));
		when(messages.findByIdAndBrandId(m.getId(), brand)).thenReturn(Optional.of(m));
		when(messages.findById(m.getId())).thenReturn(Optional.of(m));
		return m;
	}

	@Test
	void anEmptyOrWhitespaceBodyIsRefused() {
		assertThatThrownBy(() -> service.send(pm, conversationId, "   ", null))
				.isInstanceOf(InvalidRequestException.class);
		verify(messages, never()).save(any());
	}

	@Test
	void aBodyOver4000CharactersIsRefusedAndExactly4000IsSent() {
		assertThatThrownBy(() -> service.send(pm, conversationId, "x".repeat(4001), null))
				.isInstanceOf(InvalidRequestException.class);

		assertThat(service.send(pm, conversationId, "x".repeat(4000), null).body()).hasSize(4000);
	}

	@Test
	void aReplyToAReplyIsRefused() {
		Message top = existing(pm, "top", null);
		Message reply = existing(pm, "reply", top.getId());

		assertThatThrownBy(() -> service.send(pm, conversationId, "deeper", reply.getId()))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("one level");
	}

	@Test
	void aReplyToAMessageInAnotherConversationIsRefused() {
		Message elsewhere = new Message(brand, UUID.randomUUID(), ParticipantKind.STAFF, pm.id(), "other", null);
		ReflectionTestUtils.setField(elsewhere, "id", UUID.randomUUID());
		when(messages.findByIdAndBrandId(elsewhere.getId(), brand)).thenReturn(Optional.of(elsewhere));

		assertThatThrownBy(() -> service.send(pm, conversationId, "hi", elsewhere.getId()))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("not in this conversation");
	}

	@Test
	void sendingMovesTheAuthorsOwnWatermarkAndTouchesTheConversation() {
		ChatViews.MessageView sent = service.send(pm, conversationId, "hello", null);

		verify(reads).advance(brand, conversationId, "STAFF", pm.id(), sent.id(), sent.createdAt());
		assertThat(conversation.getLastMessageAt()).isEqualTo(sent.createdAt());
		verify(events).publishEvent(any(ChatChanged.class));
	}

	@Test
	void onlyTheAuthorCanEdit() {
		Message theirs = existing(client, "from the client", null);

		assertThatThrownBy(() -> service.edit(pm, theirs.getId(), "rewritten"))
				.isInstanceOf(ForbiddenException.class);
		assertThat(theirs.getBody()).isEqualTo("from the client");
	}

	@Test
	void editKeepsTheOriginalTextInTheAudit() {
		Message mine = existing(pm, "old", null);

		service.edit(pm, mine.getId(), "new");

		assertThat(mine.getBody()).isEqualTo("new");
		assertThat(mine.getEditedAt()).isNotNull();
		verify(audit).recordEvent("MESSAGE", mine.getId(), AuditAction.CHAT_MESSAGE_EDITED, pm.id(),
				Map.of("body", "old"), Map.of("body", "new"));
	}

	@Test
	void aClientsDeleteIsAuditedAsTheClientWithTheTextKept() {
		Message mine = existing(client, "old", null);

		service.delete(client, mine.getId());

		assertThat(mine.isDeleted()).isTrue();
		assertThat(mine.getBody()).isEmpty();
		verify(audit).recordPortalEvent(brand, PortalAudience.CLIENT, "MESSAGE", mine.getId(),
				AuditAction.CHAT_MESSAGE_DELETED, Map.of("body", "old"), null);
	}

	@Test
	void aDeletedMessageCannotBeEditedOrReactedTo() {
		Message gone = existing(pm, "old", null);
		gone.delete(Instant.now());

		assertThatThrownBy(() -> service.edit(pm, gone.getId(), "again")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.react(pm, gone.getId(), Reaction.HEART, true))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aViewerCannotWrite() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		when(access.requireWrite(eq(gm), any())).thenThrow(new ForbiddenException("read only"));
		when(access.level(eq(gm), any())).thenReturn(ChatAccessLevel.VIEWER);
		Message someone = existing(pm, "hi", null);

		assertThatThrownBy(() -> service.send(gm, conversationId, "hi", null)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> service.react(gm, someone.getId(), Reaction.HEART, true))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> service.markRead(gm, conversationId, someone.getId()))
				.isInstanceOf(ForbiddenException.class);
		verify(messages, never()).save(any());
		verify(reads, never()).advance(any(), any(), anyString(), any(), any(), any());
	}

	@Test
	void the31stMessageInAMinuteIsRefused() {
		for (int i = 0; i < ChatRateLimiter.PER_MINUTE; i++) {
			service.send(pm, conversationId, "message " + i, null);
		}

		assertThatThrownBy(() -> service.send(pm, conversationId, "one too many", null))
				.isInstanceOf(ChatRateLimiter.TooManyMessagesException.class);
	}

	@Test
	void reactingTwiceIsIdempotentAndUnreactingRemovesTheRow() {
		Message m = existing(pm, "hi", null);
		MessageReaction held = new MessageReaction(brand, m.getId(), ParticipantKind.CLIENT, client.id(), Reaction.THANKS);
		when(reactions.findByBrandIdAndMessageIdAndReactorKindAndReactorIdAndReaction(brand, m.getId(),
				ParticipantKind.CLIENT, client.id(), Reaction.THANKS)).thenReturn(Optional.of(held));

		service.react(client, m.getId(), Reaction.THANKS, true);
		verify(reactions, never()).save(any());

		service.react(client, m.getId(), Reaction.THANKS, false);
		verify(reactions).delete(held);
	}

	@Test
	void aMalformedCursorIsRefused() {
		assertThatThrownBy(() -> service.messages(pm, conversationId, "not-a-cursor", null, 20))
				.isInstanceOf(InvalidRequestException.class);
		verify(query, never()).page(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
				org.mockito.ArgumentMatchers.anyInt());
	}
}
