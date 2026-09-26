package com.ie.evalos.chat;

import java.util.UUID;

/**
 * Something about a conversation changed and has committed. Published inside the writing
 * transaction; `ChatFanout` (Task 10) and `ChatPushNotifier` (Task 11) listen AFTER_COMMIT.
 */
public record ChatChanged(UUID brandId, UUID conversationId, Kind kind, Object payload) {

	public enum Kind {
		MEMBERS_CHANGED, READ_ONLY, MESSAGE_CREATED, MESSAGE_EDITED, MESSAGE_DELETED, REACTIONS_CHANGED, READ_MOVED
	}
}
