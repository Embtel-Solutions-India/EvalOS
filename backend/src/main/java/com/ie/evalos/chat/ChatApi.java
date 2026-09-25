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

	/** A browser's PushSubscription, as the Push API serialises it. */
	public record SubscribeRequest(@NotBlank String endpoint, @NotNull @jakarta.validation.Valid Keys keys) {

		public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
		}
	}

	public record UnsubscribeRequest(@NotBlank String endpoint) {
	}

	private final MessageService messages;
	private final ClientAccountRepository accounts;
	private final com.ie.evalos.chat.live.ChatRealtime realtime;
	private final com.ie.evalos.chat.live.ChatTyping typing;
	private final com.ie.evalos.chat.live.ChatPresence presence;
	private final ChatAccess access;
	private final ConversationMemberRepository members;
	private final com.ie.evalos.chat.push.PushSettings push;
	private final com.ie.evalos.chat.push.PushSubscriptionRepository subscriptions;

	ChatApi(MessageService messages, ClientAccountRepository accounts, com.ie.evalos.chat.live.ChatRealtime realtime,
			com.ie.evalos.chat.live.ChatTyping typing, com.ie.evalos.chat.live.ChatPresence presence, ChatAccess access,
			ConversationMemberRepository members, com.ie.evalos.chat.push.PushSettings push,
			com.ie.evalos.chat.push.PushSubscriptionRepository subscriptions) {
		this.messages = messages;
		this.accounts = accounts;
		this.realtime = realtime;
		this.typing = typing;
		this.presence = presence;
		this.access = access;
		this.members = members;
		this.push = push;
		this.subscriptions = subscriptions;
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

	public void typing(ChatIdentity who, UUID conversationId) {
		typing.typing(who, conversationId);
	}

	/**
	 * Who of a conversation's current participants has an app open, keyed {@code KIND:id}. Only the
	 * conversation's own participants: presence is not a way to probe anyone else.
	 */
	@Transactional(readOnly = true)
	public java.util.Map<String, Boolean> presence(ChatIdentity who, UUID conversationId) {
		Conversation conversation = access.requireRead(who, conversationId);
		java.util.Map<String, Boolean> online = new java.util.LinkedHashMap<>();
		for (ConversationMember member : members.findByBrandIdAndConversationIdAndLeftAtIsNull(
				conversation.getBrandId(), conversationId)) {
			online.put(member.getKind() + ":" + member.getMemberId(),
					presence.isOnline(member.getKind(), member.getMemberId()));
		}
		return online;
	}

	// --- push (Unit 57 §6, D37) -------------------------------------------------------------

	public java.util.Map<String, String> publicKey() {
		if (!push.enabled()) {
			throw new com.ie.evalos.chat.push.PushUnavailableException();
		}
		return java.util.Map.of("publicKey", push.publicKey());
	}

	/**
	 * Remembers this browser for the caller. <strong>A browser belongs to whoever subscribed it last</strong>
	 * — on a shared computer the previous person's row is replaced, never kept beside it.
	 */
	@Transactional
	public void subscribe(ChatIdentity who, SubscribeRequest request) {
		if (who.isViewerRole() || who.brandId() == null) {
			throw new ForbiddenException(
					"Oversight does not take part in conversations, so there is nothing to notify you about.");
		}
		requireKnownPushService(request.endpoint());
		subscriptions.findByEndpoint(request.endpoint()).ifPresent((previous) -> {
			subscriptions.delete(previous);
			subscriptions.flush();
		});
		subscriptions.save(new com.ie.evalos.chat.push.PushSubscription(who.brandId(), who.kind(), who.id(),
				request.endpoint(), request.keys().p256dh(), request.keys().auth()));
	}

	/**
	 * The browser push services an endpoint may point at. The endpoint is a URL this server will POST
	 * to, so it is a trust boundary: only https to one of these hosts, never an internal address.
	 * Chrome/Edge-on-Chromium use FCM, Firefox Mozilla's service, Safari Apple's, legacy Edge WNS.
	 */
	private static final java.util.List<String> PUSH_HOSTS = java.util.List.of("fcm.googleapis.com",
			"updates.push.services.mozilla.com");
	private static final java.util.List<String> PUSH_HOST_SUFFIXES = java.util.List.of(".push.apple.com",
			".notify.windows.com");

	static void requireKnownPushService(String endpoint) {
		java.net.URI uri;
		try {
			uri = new java.net.URI(endpoint);
		}
		catch (java.net.URISyntaxException malformed) {
			throw new com.ie.evalos.common.InvalidRequestException("That is not a push subscription endpoint.");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
		boolean known = PUSH_HOSTS.contains(host) || PUSH_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !known) {
			throw new com.ie.evalos.common.InvalidRequestException(
					"Notifications can only be delivered through a browser's own push service.");
		}
	}

	/** Forgets this browser, but only if it is the caller's. */
	@Transactional
	public void unsubscribe(ChatIdentity who, UnsubscribeRequest request) {
		subscriptions.findByEndpoint(request.endpoint())
				.filter((mine) -> mine.belongsTo(who.kind(), who.id()))
				.ifPresent(subscriptions::delete);
	}

	/** An Ably TokenRequest for the caller's own channel (Unit 57 §5). */
	public com.ie.evalos.chat.live.AblyToken realtimeToken(ChatIdentity who) {
		return realtime.token(who);
	}
}
