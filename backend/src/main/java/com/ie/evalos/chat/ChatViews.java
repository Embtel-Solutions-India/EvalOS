package com.ie.evalos.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the chat API returns (Unit 57 §4). Records only; built by {@link MessageService}. */
public final class ChatViews {

	private ChatViews() {
	}

	/** Somebody currently in a conversation, with the label others see. */
	public record Participant(ParticipantKind kind, UUID id, ChatRole role, String name) {
	}

	/**
	 * One message. {@code reactions} maps each reaction to who gave it, by display name.
	 * {@code mine} is true when the caller wrote it — the only person who may edit or delete it.
	 */
	public record MessageView(UUID id, UUID conversationId, ParticipantKind authorKind, UUID authorId,
			String authorName, String body, UUID parentId, int replyCount, Instant createdAt, Instant editedAt,
			boolean deleted, Map<Reaction, List<String>> reactions, boolean mine) {
	}

	/** An inbox row: the conversation with its case context. {@code unread} is 0 for a viewer. */
	public record ConversationView(UUID id, UUID caseId, String caseCode, String serviceType, String stage,
			ConversationType type, ConversationStatus status, ChatAccessLevel access, long unread,
			MessageView lastMessage, List<Participant> participants, Instant lastMessageAt) {
	}

	/** A keyset page. {@code nextCursor} is null at the end; pass it back as {@code before} (or {@code after}). */
	public record Page<T>(List<T> items, String nextCursor) {
	}

	/** Where one reader has read up to — "seen by". */
	public record ReaderMark(ParticipantKind kind, UUID id, String name, UUID lastReadMessageId) {
	}

	public record ReadState(UUID conversationId, List<ReaderMark> readers) {
	}
}
