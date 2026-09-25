package com.ie.evalos.chat.live;

import java.util.UUID;

/** The data of every chat Ably message; the Ably message name is {@code type}. The contract for phases 2–3. */
public record ChatEnvelope(String type, UUID conversationId, Object data) {
}
