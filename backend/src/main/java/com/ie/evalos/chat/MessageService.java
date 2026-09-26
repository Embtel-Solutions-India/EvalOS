package com.ie.evalos.chat;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.service.AuditService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages, threads, reactions, reads, search and unread counts (Unit 57 §3).
 *
 * <p><strong>Every method passes {@link ChatAccess} first.</strong> Writes need MEMBER on an open
 * conversation; reads need MEMBER or VIEWER. Each write publishes a {@link ChatChanged} inside its
 * transaction, which the live layer fans out after commit.
 *
 * <p><strong>Own messages only.</strong> Nobody edits or deletes another person's message; an edit or
 * delete keeps the original text in {@code audit_event}, so a client conversation stays a record.
 */
@Service
public class MessageService {

	static final int MAX_BODY = 4000;
	private static final String OBJECT_TYPE = "MESSAGE";

	private final ChatAccess access;
	private final MessageRepository messages;
	private final MessageReactionRepository reactions;
	private final MessageReadRepository reads;
	private final ConversationRepository conversations;
	private final ChatInboxQuery query;
	private final AuditService audit;
	private final ApplicationEventPublisher events;
	private final ChatRateLimiter limiter;

	MessageService(ChatAccess access, MessageRepository messages, MessageReactionRepository reactions,
			MessageReadRepository reads, ConversationRepository conversations, ChatInboxQuery query, AuditService audit,
			ApplicationEventPublisher events, ChatRateLimiter limiter) {
		this.access = access;
		this.messages = messages;
		this.reactions = reactions;
		this.reads = reads;
		this.conversations = conversations;
		this.query = query;
		this.audit = audit;
		this.events = events;
		this.limiter = limiter;
	}

	// --- writes ------------------------------------------------------------------------------

	@Transactional
	public ChatViews.MessageView send(ChatIdentity who, UUID conversationId, String body, UUID parentId) {
		Conversation conversation = access.requireWrite(who, conversationId);
		String text = validBody(body);
		if (parentId != null) {
			Message parent = messages.findByIdAndBrandId(parentId, conversation.getBrandId())
					.filter((m) -> m.getConversationId().equals(conversationId))
					.orElseThrow(() -> new InvalidRequestException("That message is not in this conversation."));
			if (parent.getParentMessageId() != null) {
				throw new InvalidRequestException("Replies are one level deep: reply to the original message.");
			}
			if (parent.isDeleted()) {
				throw new InvalidRequestException("That message was deleted, so it cannot be replied to.");
			}
		}
		limiter.check(who);

		Message saved = messages.save(new Message(conversation.getBrandId(), conversationId, who.kind(), who.id(),
				text, parentId));
		conversation.touch(saved.getCreatedAt());
		conversations.save(conversation);
		reads.advance(conversation.getBrandId(), conversationId, who.kind().name(), who.id(), saved.getId(),
				saved.getCreatedAt());

		ChatViews.MessageView view = views(who, List.of(rowOf(saved, 0))).get(0);
		publish(conversation, ChatChanged.Kind.MESSAGE_CREATED, view);
		return view;
	}

	@Transactional
	public ChatViews.MessageView edit(ChatIdentity who, UUID messageId, String body) {
		Message message = ownForWrite(who, messageId);
		String text = validBody(body);
		String previous = message.getBody();
		message.edit(text, Instant.now());
		messages.save(message);
		audit(who, message, AuditAction.CHAT_MESSAGE_EDITED, Map.of("body", previous), Map.of("body", text));
		ChatViews.MessageView view = view(who, message);
		publish(message, ChatChanged.Kind.MESSAGE_EDITED, view);
		return view;
	}

	@Transactional
	public void delete(ChatIdentity who, UUID messageId) {
		Message message = ownForWrite(who, messageId);
		String previous = message.getBody();
		message.delete(Instant.now());
		messages.save(message);
		audit(who, message, AuditAction.CHAT_MESSAGE_DELETED, Map.of("body", previous), null);
		publish(message, ChatChanged.Kind.MESSAGE_DELETED, view(who, message));
	}

	@Transactional
	public ChatViews.MessageView react(ChatIdentity who, UUID messageId, Reaction reaction, boolean on) {
		Message message = forWrite(who, messageId);
		if (message.isDeleted()) {
			throw new InvalidRequestException("That message was deleted.");
		}
		var held = reactions.findByBrandIdAndMessageIdAndReactorKindAndReactorIdAndReaction(message.getBrandId(),
				message.getId(), who.kind(), who.id(), reaction);
		if (on && held.isEmpty()) {
			reactions.save(new MessageReaction(message.getBrandId(), message.getId(), who.kind(), who.id(), reaction));
		}
		if (!on) {
			held.ifPresent(reactions::delete);
		}
		ChatViews.MessageView view = view(who, message);
		publish(message, ChatChanged.Kind.REACTIONS_CHANGED, view);
		return view;
	}

	/** Moves the caller's watermark forward (never back). Members only: oversight leaves no trace. */
	@Transactional
	public void markRead(ChatIdentity who, UUID conversationId, UUID messageId) {
		Conversation conversation = access.requireRead(who, conversationId);
		if (access.level(who, conversation) != ChatAccessLevel.MEMBER) {
			throw new ForbiddenException("Oversight reads conversations; it does not take part in them.");
		}
		Message message = messages.findByIdAndBrandId(messageId, conversation.getBrandId())
				.filter((m) -> m.getConversationId().equals(conversationId))
				.orElseThrow(() -> new InvalidRequestException("That message is not in this conversation."));
		reads.advance(conversation.getBrandId(), conversationId, who.kind().name(), who.id(), message.getId(),
				message.getCreatedAt());
		String name = query.names(List.of(entry(who.kind(), who.id()))).get(who.kind() + ":" + who.id());
		publish(conversation, ChatChanged.Kind.READ_MOVED,
				new ChatViews.ReaderMark(who.kind(), who.id(), name, message.getId()));
	}

	// --- reads -------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ChatViews.Page<ChatViews.ConversationView> inbox(ChatIdentity who, UUID caseId, ConversationType type,
			ConversationStatus status, String cursor, int limit) {
		int size = clamp(limit, 50);
		Cursor after = Cursor.parse(cursor);
		List<UUID> ids = query.inbox(who, caseId, type, status, after == null ? null : after.at(),
				after == null ? null : after.id(), size);
		Map<UUID, Conversation> byId = conversations.findAllById(ids).stream()
				.collect(Collectors.toMap(Conversation::getId, Function.identity()));
		List<Conversation> ordered = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
		List<ChatViews.ConversationView> items = conversationViews(who, ordered);
		String next = null;
		if (ordered.size() == size) {
			Conversation last = ordered.get(ordered.size() - 1);
			Instant at = last.getLastMessageAt() != null ? last.getLastMessageAt() : last.getCreatedAt();
			next = new Cursor(at, last.getId()).toString();
		}
		return new ChatViews.Page<>(items, next);
	}

	@Transactional(readOnly = true)
	public ChatViews.ConversationView conversation(ChatIdentity who, UUID conversationId) {
		return conversationViews(who, List.of(access.requireRead(who, conversationId))).get(0);
	}

	@Transactional(readOnly = true)
	public ChatViews.Page<ChatViews.MessageView> messages(ChatIdentity who, UUID conversationId, String before,
			String after, int limit) {
		Conversation conversation = access.requireRead(who, conversationId);
		int size = clamp(limit, 100);
		Cursor forward = Cursor.parse(after);
		Cursor backward = Cursor.parse(before);
		boolean newestFirst = forward == null;
		Cursor from = newestFirst ? backward : forward;
		List<ChatInboxQuery.Row> rows = query.page(conversation.getBrandId(), conversationId,
				from == null ? null : from.at(), from == null ? null : from.id(), newestFirst, size);
		String next = rows.size() == size
				? new Cursor(rows.get(rows.size() - 1).createdAt(), rows.get(rows.size() - 1).id()).toString()
				: null;
		return new ChatViews.Page<>(views(who, rows), next);
	}

	@Transactional(readOnly = true)
	public List<ChatViews.MessageView> replies(ChatIdentity who, UUID messageId) {
		Message parent = messages.findById(messageId)
				.orElseThrow(() -> new ForbiddenException(ChatAccess.NOT_YOURS));
		access.requireRead(who, parent.getConversationId());
		return views(who, query.replies(parent.getBrandId(), parent.getId()));
	}

	@Transactional(readOnly = true)
	public ChatViews.ReadState readState(ChatIdentity who, UUID conversationId) {
		Conversation conversation = access.requireRead(who, conversationId);
		List<MessageRead> marks = reads.findByBrandIdAndConversationId(conversation.getBrandId(), conversationId);
		Map<String, String> names = query.names(marks.stream()
				.map((r) -> entry(r.getReaderKind(), r.getReaderId())).toList());
		return new ChatViews.ReadState(conversationId, marks.stream()
				.map((r) -> new ChatViews.ReaderMark(r.getReaderKind(), r.getReaderId(),
						names.get(r.getReaderKind() + ":" + r.getReaderId()), r.getLastReadMessageId()))
				.toList());
	}

	@Transactional(readOnly = true)
	public List<ChatViews.MessageView> search(ChatIdentity who, String q, UUID caseId, ConversationType type,
			int limit) {
		if (q == null || q.isBlank()) {
			throw new InvalidRequestException("Type something to search for.");
		}
		return views(who, query.search(who, q.strip(), caseId, type, clamp(limit, 50)));
	}

	@Transactional(readOnly = true)
	public long unreadTotal(ChatIdentity who) {
		return who.isViewerRole() ? 0 : query.unreadTotal(who);
	}

	// --- helpers -----------------------------------------------------------------------------

	/** The message, proved writable by the caller (member, open conversation), author or not. */
	private Message forWrite(ChatIdentity who, UUID messageId) {
		Message message = messages.findById(messageId)
				.orElseThrow(() -> new ForbiddenException(ChatAccess.NOT_YOURS));
		access.requireWrite(who, message.getConversationId());
		return message;
	}

	/** As {@link #forWrite}, and the caller wrote it, and it is not already deleted. */
	private Message ownForWrite(ChatIdentity who, UUID messageId) {
		Message message = forWrite(who, messageId);
		if (!message.isAuthoredBy(who.kind(), who.id())) {
			throw new ForbiddenException("Only the author can change a message.");
		}
		if (message.isDeleted()) {
			throw new InvalidRequestException("That message was deleted.");
		}
		return message;
	}

	private static String validBody(String body) {
		String text = body == null ? "" : body.strip();
		if (text.isEmpty()) {
			throw new InvalidRequestException("A message needs some text.");
		}
		if (text.length() > MAX_BODY) {
			throw new InvalidRequestException("A message can be at most " + MAX_BODY + " characters.");
		}
		return text;
	}

	private void audit(ChatIdentity who, Message message, AuditAction action, Object before, Object after) {
		if (who.kind() == ParticipantKind.STAFF) {
			audit.recordEvent(OBJECT_TYPE, message.getId(), action, who.id(), before, after);
		}
		else {
			PortalAudience audience = who.kind() == ParticipantKind.CLIENT ? PortalAudience.CLIENT : PortalAudience.EXPERT;
			audit.recordPortalEvent(message.getBrandId(), audience, OBJECT_TYPE, message.getId(), action, before, after);
		}
	}

	private void publish(Conversation conversation, ChatChanged.Kind kind, Object payload) {
		events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(), kind, payload));
	}

	private void publish(Message message, ChatChanged.Kind kind, Object payload) {
		events.publishEvent(new ChatChanged(message.getBrandId(), message.getConversationId(), kind, payload));
	}

	private ChatViews.MessageView view(ChatIdentity who, Message message) {
		int replies = 0;
		ChatInboxQuery.Row stored = query.one(message.getBrandId(), message.getId());
		if (stored != null) {
			replies = stored.replyCount();
		}
		return views(who, List.of(rowOf(message, replies))).get(0);
	}

	private static ChatInboxQuery.Row rowOf(Message m, int replies) {
		return new ChatInboxQuery.Row(m.getId(), m.getConversationId(), m.getAuthorKind(), m.getAuthorId(), m.getBody(),
				m.getParentMessageId(), m.getCreatedAt(), m.getEditedAt(), m.getDeletedAt(), replies);
	}

	/** Rows to views: author names and reactions in one query each, not one per message. */
	private List<ChatViews.MessageView> views(ChatIdentity who, List<ChatInboxQuery.Row> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = new ArrayList<>();
		Set<Map.Entry<ParticipantKind, UUID>> people = new HashSet<>();
		for (ChatInboxQuery.Row row : rows) {
			ids.add(row.id());
			people.add(entry(row.authorKind(), row.authorId()));
		}
		List<MessageReaction> given = reactions.findByMessageIdIn(ids);
		given.forEach((r) -> people.add(entry(r.getReactorKind(), r.getReactorId())));
		Map<String, String> names = query.names(people);

		Map<UUID, Map<Reaction, List<String>>> byMessage = new LinkedHashMap<>();
		for (MessageReaction r : given) {
			byMessage.computeIfAbsent(r.getMessageId(), (k) -> new EnumMap<>(Reaction.class))
					.computeIfAbsent(r.getReaction(), (k) -> new ArrayList<>())
					.add(names.getOrDefault(r.getReactorKind() + ":" + r.getReactorId(), ""));
		}
		return rows.stream().map((row) -> new ChatViews.MessageView(row.id(), row.conversationId(), row.authorKind(),
				row.authorId(), names.get(row.authorKind() + ":" + row.authorId()), row.body(), row.parentId(),
				row.replyCount(), row.createdAt(), row.editedAt(), row.deletedAt() != null,
				byMessage.getOrDefault(row.id(), Map.of()),
				row.authorKind() == who.kind() && row.authorId().equals(who.id()))).toList();
	}

	private List<ChatViews.ConversationView> conversationViews(ChatIdentity who, List<Conversation> list) {
		if (list.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = list.stream().map(Conversation::getId).toList();
		boolean viewer = who.isViewerRole();
		Map<UUID, Long> unread = viewer ? Map.of() : query.unread(who.kind(), who.id(), ids);
		Map<UUID, ChatInboxQuery.Row> last = query.lastMessages(ids);
		List<ChatInboxQuery.MemberRow> members = query.currentMembers(ids);
		Map<UUID, ChatInboxQuery.CaseContext> cases =
				query.caseContext(list.stream().map(Conversation::getCaseId).toList());

		Set<Map.Entry<ParticipantKind, UUID>> people = new HashSet<>();
		members.forEach((m) -> people.add(entry(m.kind(), m.id())));
		last.values().forEach((r) -> people.add(entry(r.authorKind(), r.authorId())));
		Map<String, String> names = query.names(people);

		Map<UUID, List<ChatViews.Participant>> participants = new LinkedHashMap<>();
		for (ChatInboxQuery.MemberRow m : members) {
			participants.computeIfAbsent(m.conversationId(), (k) -> new ArrayList<>())
					.add(new ChatViews.Participant(m.kind(), m.id(), m.role(), names.get(m.kind() + ":" + m.id())));
		}
		List<ChatViews.ConversationView> views = new ArrayList<>();
		for (Conversation c : list) {
			ChatInboxQuery.CaseContext context = cases.get(c.getCaseId());
			ChatInboxQuery.Row lastRow = last.get(c.getId());
			ChatViews.MessageView lastView = lastRow == null ? null
					: new ChatViews.MessageView(lastRow.id(), lastRow.conversationId(), lastRow.authorKind(),
							lastRow.authorId(), names.get(lastRow.authorKind() + ":" + lastRow.authorId()),
							lastRow.body(), lastRow.parentId(), lastRow.replyCount(), lastRow.createdAt(),
							lastRow.editedAt(), lastRow.deletedAt() != null, Map.of(),
							lastRow.authorKind() == who.kind() && lastRow.authorId().equals(who.id()));
			views.add(new ChatViews.ConversationView(c.getId(), c.getCaseId(),
					context == null ? null : context.caseCode(), context == null ? null : context.serviceType(),
					context == null ? null : context.stage(), c.getType(), c.getStatus(),
					viewer ? ChatAccessLevel.VIEWER : ChatAccessLevel.MEMBER, unread.getOrDefault(c.getId(), 0L),
					lastView, participants.getOrDefault(c.getId(), List.of()), c.getLastMessageAt()));
		}
		return views;
	}

	private static Map.Entry<ParticipantKind, UUID> entry(ParticipantKind kind, UUID id) {
		return new AbstractMap.SimpleImmutableEntry<>(kind, id);
	}

	private static int clamp(int limit, int max) {
		return Math.max(1, Math.min(limit, max));
	}

	/** A keyset position: {@code <ISO instant>|<uuid>}. */
	record Cursor(Instant at, UUID id) {

		static Cursor parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return null;
			}
			int bar = raw.indexOf('|');
			try {
				if (bar < 0) {
					throw new IllegalArgumentException();
				}
				return new Cursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
			}
			catch (IllegalArgumentException | DateTimeParseException malformed) {
				throw new InvalidRequestException("That page cursor is not valid.");
			}
		}

		@Override
		public String toString() {
			return at + "|" + id;
		}
	}

}
