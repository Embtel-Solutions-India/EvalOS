package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one gate every chat read and write passes (Unit 57 §3): every REST route, and typing.
 *
 * <p><strong>Type before membership.</strong> A client reaches CLIENT conversations and an expert
 * EXPERT ones, whatever a member row says — a bad row must not be the only thing between a client
 * and the internal conversation.
 */
@Component
public class ChatAccess {

	public static final String NOT_YOURS = "That conversation is not one you can open.";

	private final ConversationRepository conversations;
	private final ConversationMemberRepository members;

	ChatAccess(ConversationRepository conversations, ConversationMemberRepository members) {
		this.conversations = conversations;
		this.members = members;
	}

	public ChatAccessLevel level(ChatIdentity who, Conversation conversation) {
		if (who.kind() == ParticipantKind.CLIENT && conversation.getType() != ConversationType.CLIENT) {
			return ChatAccessLevel.NONE;
		}
		if (who.kind() == ParticipantKind.EXPERT && conversation.getType() != ConversationType.EXPERT) {
			return ChatAccessLevel.NONE;
		}
		boolean member = members.findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(
				conversation.getBrandId(), conversation.getId(), who.kind(), who.id()).isPresent();
		if (member) {
			return ChatAccessLevel.MEMBER;
		}
		if (who.staffRole() == Role.GM) {
			return ChatAccessLevel.VIEWER;
		}
		if (who.staffRole() == Role.BRAND_MANAGER && conversation.getBrandId().equals(who.brandId())) {
			return ChatAccessLevel.VIEWER;
		}
		return ChatAccessLevel.NONE;
	}

	@Transactional(readOnly = true)
	public Conversation requireRead(ChatIdentity who, UUID conversationId) {
		Conversation conversation = load(who, conversationId).orElseThrow(() -> new ForbiddenException(NOT_YOURS));
		if (level(who, conversation) == ChatAccessLevel.NONE) {
			throw new ForbiddenException(NOT_YOURS);
		}
		return conversation;
	}

	@Transactional(readOnly = true)
	public Conversation requireWrite(ChatIdentity who, UUID conversationId) {
		Conversation conversation = load(who, conversationId).orElseThrow(() -> new ForbiddenException(NOT_YOURS));
		ChatAccessLevel level = level(who, conversation);
		if (level == ChatAccessLevel.NONE) {
			throw new ForbiddenException(NOT_YOURS);
		}
		if (level == ChatAccessLevel.VIEWER) {
			throw new ForbiddenException("Oversight reads conversations; it does not take part in them.");
		}
		if (conversation.isReadOnly()) {
			throw new ConversationReadOnlyException();
		}
		return conversation;
	}

	/** The GM is Tier.ALL and carries no brand; everyone else is looked up inside their own brand. */
	private Optional<Conversation> load(ChatIdentity who, UUID conversationId) {
		return who.staffRole() == Role.GM ? conversations.findById(conversationId)
				: conversations.findByIdAndBrandId(conversationId, who.brandId());
	}
}
