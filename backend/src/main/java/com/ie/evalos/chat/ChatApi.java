package com.ie.evalos.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.security.PortalPrincipal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the three chat surfaces share (Unit 57 §4): who the caller is on each surface, the request
 * bodies, and one delegate per route. The controllers stay thin; the rules live in the services.
 */
@Component
public class ChatApi {

	public record SendRequest(@NotBlank @Size(max = MessageService.MAX_BODY) String body, UUID parentId) {
	}

	public record EditRequest(@NotBlank @Size(max = MessageService.MAX_BODY) String body) {
	}

	public record ReadRequest(@NotNull UUID messageId) {
	}

	private final MessageService messages;
	private final ClientAccountRepository accounts;

	ChatApi(MessageService messages, ClientAccountRepository accounts) {
		this.messages = messages;
		this.accounts = accounts;
	}

	// --- identity, portal surfaces (staff comes straight from the session) ------------------

	/**
	 * A client, by account. An account token names the account; a party link names only a GHL
	 * contact, resolved inside the token's own brand — the two arms {@code ClientApplicationService}
	 * uses.
	 */
	@Transactional(readOnly = true)
	public ChatIdentity client() {
		PortalPrincipal principal = PortalPrincipal.current(PortalAudience.CLIENT);
		UUID account = principal.namesAnAccountDirectly() ? principal.clientAccountId()
				: Optional.ofNullable(principal.ghlContactId())
						.flatMap((contact) -> accounts.findByBrandIdAndGhlContactId(principal.brandId(), contact))
						.map(ClientAccount::getId)
						.orElseThrow(() -> new ForbiddenException("This link does not admit you to that"));
		return ChatIdentity.client(account, principal.brandId());
	}

	public ChatIdentity expert() {
		PortalPrincipal principal = PortalPrincipal.current(PortalAudience.EXPERT);
		if (principal.expertId() == null) {
			throw new ForbiddenException("This link does not admit you to that");
		}
		return ChatIdentity.expert(principal.expertId(), principal.brandId());
	}

	// --- routes -----------------------------------------------------------------------------

	public ChatViews.Page<ChatViews.ConversationView> inbox(ChatIdentity who, UUID caseId, ConversationType type,
			ConversationStatus status, String cursor, int limit) {
		return messages.inbox(who, caseId, type, status, cursor, limit);
	}

	public ChatViews.ConversationView conversation(ChatIdentity who, UUID id) {
		return messages.conversation(who, id);
	}

	public ChatViews.Page<ChatViews.MessageView> messages(ChatIdentity who, UUID id, String before, String after,
			int limit) {
		return messages.messages(who, id, before, after, limit);
	}

	public ChatViews.ReadState readState(ChatIdentity who, UUID id) {
		return messages.readState(who, id);
	}

	public List<ChatViews.MessageView> replies(ChatIdentity who, UUID messageId) {
		return messages.replies(who, messageId);
	}

	public ChatViews.MessageView send(ChatIdentity who, UUID conversationId, SendRequest request) {
		return messages.send(who, conversationId, request.body(), request.parentId());
	}

	public ChatViews.MessageView edit(ChatIdentity who, UUID messageId, EditRequest request) {
		return messages.edit(who, messageId, request.body());
	}

	public void delete(ChatIdentity who, UUID messageId) {
		messages.delete(who, messageId);
	}

	public ChatViews.MessageView react(ChatIdentity who, UUID messageId, Reaction reaction, boolean on) {
		return messages.react(who, messageId, reaction, on);
	}

	public void read(ChatIdentity who, UUID conversationId, ReadRequest request) {
		messages.markRead(who, conversationId, request.messageId());
	}

	public List<ChatViews.MessageView> search(ChatIdentity who, String q, UUID caseId, ConversationType type,
			int limit) {
		return messages.search(who, q, caseId, type, limit);
	}

	public long unread(ChatIdentity who) {
		return messages.unreadTotal(who);
	}
}
