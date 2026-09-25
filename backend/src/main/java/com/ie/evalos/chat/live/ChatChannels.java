package com.ie.evalos.chat.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.Role;

/**
 * Ably channel names and token capabilities for case chat (Unit 57 §5, D50).
 *
 * <p><strong>One private channel per person, and no token may publish.</strong> The backend decides
 * who receives each event and publishes it into each recipient's own channel, so a capability never
 * names a conversation and never has to change when membership does.
 */
public final class ChatChannels {

	private static final ObjectMapper JSON = new ObjectMapper();

	private ChatChannels() {
	}

	public static String personal(ParticipantKind kind, UUID id) {
		return "chat:user:" + kind + ":" + id;
	}

	public static String view(UUID brandId, UUID conversationId) {
		return "chat:view:" + brandId + ":" + conversationId;
	}

	public static String clientId(ChatIdentity who) {
		return who.kind() + ":" + who.id();
	}

	public static String capability(ChatIdentity who) {
		Map<String, List<String>> caps = new LinkedHashMap<>();
		caps.put(personal(who.kind(), who.id()), List.of("subscribe", "presence"));
		if (who.staffRole() == Role.GM) {
			caps.put("chat:view:*", List.of("subscribe"));
		}
		else if (who.staffRole() == Role.BRAND_MANAGER && who.brandId() != null) {
			caps.put("chat:view:" + who.brandId() + ":*", List.of("subscribe"));
		}
		try {
			return JSON.writeValueAsString(caps);
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
