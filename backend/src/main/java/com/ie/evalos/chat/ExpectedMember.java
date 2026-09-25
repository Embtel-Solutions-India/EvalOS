package com.ie.evalos.chat;

import java.util.UUID;

/** A participant a conversation should have now. */
public record ExpectedMember(ParticipantKind kind, UUID id, ChatRole role) {
}
