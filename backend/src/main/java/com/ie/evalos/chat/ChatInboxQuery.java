package com.ie.evalos.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Role;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The chat reads that need SQL rather than derived queries (Unit 57 §3): keyset paging, the inbox,
 * unread counts, search and display names.
 *
 * <p><strong>Brand rule, stated per method.</strong> Every method either filters on a brand the
 * caller's identity carries, or starts from ids an earlier, brand-filtered read produced. The GM is
 * the one caller with no brand, because the GM is {@code Tier.ALL}. Every value is a bind parameter.
 */
@Component
public class ChatInboxQuery {

	/** One message as stored, plus how many live replies it has. */
	public record Row(UUID id, UUID conversationId, ParticipantKind authorKind, UUID authorId, String body,
			UUID parentId, Instant createdAt, Instant editedAt, Instant deletedAt, int replyCount) {
	}

	/** A current member of a conversation. */
	public record MemberRow(UUID conversationId, ParticipantKind kind, UUID id, ChatRole role) {
	}

	private static final String MESSAGE_COLUMNS = """
			m.id, m.conversation_id, m.author_kind, m.author_id, m.body, m.parent_message_id, m.created_at,
			m.edited_at, m.deleted_at,
			(SELECT count(*) FROM messages r WHERE r.parent_message_id = m.id AND r.deleted_at IS NULL) AS reply_count
			""";

	private final JdbcTemplate jdbc;

	ChatInboxQuery(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The conversations a caller's inbox holds, newest activity first, one keyset page.
	 *
	 * <p>Brand: a member's conversations are reached through their own current member rows and the
	 * identity's brand; a Brand Manager's through their brand; the GM's across brands.
	 */
	public List<UUID> inbox(ChatIdentity who, UUID caseId, ConversationType type, ConversationStatus status,
			Instant cursorAt, UUID cursorId, int limit) {
		StringBuilder sql = new StringBuilder("SELECT c.id FROM conversations c ");
		List<Object> args = new ArrayList<>();
		if (who.staffRole() == Role.GM) {
			sql.append("WHERE TRUE ");
		}
		else if (who.staffRole() == Role.BRAND_MANAGER) {
			sql.append("WHERE c.brand_id = ? ");
			args.add(who.brandId());
		}
		else {
			sql.append("JOIN conversation_members m ON m.conversation_id = c.id AND m.left_at IS NULL "
					+ "AND m.member_kind = ? AND m.member_id = ? WHERE c.brand_id = ? ");
			args.add(who.kind().name());
			args.add(who.id());
			args.add(who.brandId());
		}
		if (caseId != null) {
			sql.append("AND c.case_id = ? ");
			args.add(caseId);
		}
		if (type != null) {
			sql.append("AND c.type = ? ");
			args.add(type.name());
		}
		if (status != null) {
			sql.append("AND c.status = ? ");
			args.add(status.name());
		}
		if (cursorAt != null) {
			sql.append("AND (coalesce(c.last_message_at, c.created_at), c.id) < (?, ?) ");
			args.add(Timestamp.from(cursorAt));
			args.add(cursorId);
		}
		sql.append("ORDER BY coalesce(c.last_message_at, c.created_at) DESC, c.id DESC LIMIT ?");
		args.add(limit);
		return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
	}

	/**
	 * One keyset page of a conversation's top-level messages. {@code newestFirst} pages backwards
	 * from the cursor (history); otherwise forwards (the reconnect catch-up).
	 *
	 * <p>Brand: filtered on the conversation's own brand, which the caller's access was proved on.
	 */
	public List<Row> page(UUID brandId, UUID conversationId, Instant cursorAt, UUID cursorId, boolean newestFirst,
			int limit) {
		String compare = newestFirst ? "<" : ">";
		String order = newestFirst ? "DESC" : "ASC";
		String cursor = cursorAt == null ? "" : "AND (m.created_at, m.id) " + compare + " (?, ?) ";
		String sql = "SELECT " + MESSAGE_COLUMNS + " FROM messages m WHERE m.brand_id = ? AND m.conversation_id = ? "
				+ "AND m.parent_message_id IS NULL " + cursor
				+ "ORDER BY m.created_at " + order + ", m.id " + order + " LIMIT ?";
		List<Object> args = new ArrayList<>(List.of(brandId, conversationId));
		if (cursorAt != null) {
			args.add(Timestamp.from(cursorAt));
			args.add(cursorId);
		}
		args.add(limit);
		return jdbc.query(sql, ChatInboxQuery::row, args.toArray());
	}

	/** The replies to one message, oldest first. Brand: the conversation's. */
	public List<Row> replies(UUID brandId, UUID parentId) {
		return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM messages m WHERE m.brand_id = ? "
				+ "AND m.parent_message_id = ? ORDER BY m.created_at, m.id", ChatInboxQuery::row, brandId, parentId);
	}

	/** One message by id, with its reply count. Brand: the conversation's. */
	public Row one(UUID brandId, UUID messageId) {
		List<Row> found = jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM messages m WHERE m.brand_id = ? AND m.id = ?",
				ChatInboxQuery::row, brandId, messageId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** The newest message of each conversation, replies included. Ids come from a scoped inbox read. */
	public Map<UUID, Row> lastMessages(Collection<UUID> conversationIds) {
		if (conversationIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Row> last = new HashMap<>();
		jdbc.query("SELECT DISTINCT ON (m.conversation_id) " + MESSAGE_COLUMNS + " FROM messages m "
				+ "WHERE m.conversation_id IN (" + placeholders(conversationIds) + ") "
				+ "ORDER BY m.conversation_id, m.created_at DESC, m.id DESC",
				(rs) -> {
					Row r = row(rs, 0);
					last.put(r.conversationId(), r);
				}, conversationIds.toArray());
		return last;
	}

	/** Current members of these conversations. Ids come from a scoped inbox read or a proved access. */
	public List<MemberRow> currentMembers(Collection<UUID> conversationIds) {
		if (conversationIds.isEmpty()) {
			return List.of();
		}
		return jdbc.query("SELECT conversation_id, member_kind, member_id, member_role FROM conversation_members "
				+ "WHERE left_at IS NULL AND conversation_id IN (" + placeholders(conversationIds) + ") ORDER BY created_at",
				(rs, n) -> new MemberRow(rs.getObject(1, UUID.class), ParticipantKind.valueOf(rs.getString(2)),
						rs.getObject(3, UUID.class), ChatRole.valueOf(rs.getString(4))),
				conversationIds.toArray());
	}

	/**
	 * Unread messages per conversation for one reader: from other people, not deleted, after the
	 * reader's watermark (or all of them when there is none). Ids come from a scoped inbox read.
	 */
	public Map<UUID, Long> unread(ParticipantKind kind, UUID readerId, Collection<UUID> conversationIds) {
		if (conversationIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Long> counts = new HashMap<>();
		List<Object> args = new ArrayList<>(List.of(kind.name(), readerId, kind.name(), readerId));
		args.addAll(conversationIds);
		jdbc.query("SELECT m.conversation_id, count(*) FROM messages m "
				+ "LEFT JOIN message_reads r ON r.conversation_id = m.conversation_id AND r.reader_kind = ? AND r.reader_id = ? "
				+ "WHERE m.deleted_at IS NULL AND NOT (m.author_kind = ? AND m.author_id = ?) "
				+ "AND (r.last_read_at IS NULL OR m.created_at > r.last_read_at) "
				+ "AND m.conversation_id IN (" + placeholders(conversationIds) + ") GROUP BY m.conversation_id",
				(rs) -> {
					counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
				}, args.toArray());
		return counts;
	}

	/** Total unread across every conversation the reader is currently a member of, in their brand. */
	public long unreadTotal(ChatIdentity who) {
		Long total = jdbc.queryForObject("SELECT count(*) FROM messages m "
				+ "JOIN conversation_members cm ON cm.conversation_id = m.conversation_id AND cm.left_at IS NULL "
				+ "AND cm.member_kind = ? AND cm.member_id = ? "
				+ "LEFT JOIN message_reads r ON r.conversation_id = m.conversation_id AND r.reader_kind = ? AND r.reader_id = ? "
				+ "WHERE m.brand_id = ? AND m.deleted_at IS NULL AND NOT (m.author_kind = ? AND m.author_id = ?) "
				+ "AND (r.last_read_at IS NULL OR m.created_at > r.last_read_at)",
				Long.class, who.kind().name(), who.id(), who.kind().name(), who.id(), who.brandId(), who.kind().name(),
				who.id());
		return total == null ? 0 : total;
	}

	/**
	 * Full-text search over the conversations the caller can open, newest first, deleted excluded.
	 * Brand: as {@link #inbox}.
	 */
	public List<Row> search(ChatIdentity who, String q, UUID caseId, ConversationType type, int limit) {
		StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS
				+ " FROM messages m JOIN conversations c ON c.id = m.conversation_id ");
		List<Object> args = new ArrayList<>();
		if (who.staffRole() == Role.GM) {
			sql.append("WHERE TRUE ");
		}
		else if (who.staffRole() == Role.BRAND_MANAGER) {
			sql.append("WHERE c.brand_id = ? ");
			args.add(who.brandId());
		}
		else {
			sql.append("JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.left_at IS NULL "
					+ "AND cm.member_kind = ? AND cm.member_id = ? WHERE c.brand_id = ? ");
			args.add(who.kind().name());
			args.add(who.id());
			args.add(who.brandId());
		}
		sql.append("AND m.deleted_at IS NULL AND m.search @@ websearch_to_tsquery('simple', ?) ");
		args.add(q);
		if (caseId != null) {
			sql.append("AND c.case_id = ? ");
			args.add(caseId);
		}
		if (type != null) {
			sql.append("AND c.type = ? ");
			args.add(type.name());
		}
		sql.append("ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
		args.add(limit);
		return jdbc.query(sql.toString(), ChatInboxQuery::row, args.toArray());
	}

	/**
	 * Display names keyed {@code KIND:id}: staff by display name, a client by first and last name
	 * ("Client" when blank), an expert by full name. Ids come from rows already brand-scoped.
	 */
	public Map<String, String> names(Collection<Map.Entry<ParticipantKind, UUID>> people) {
		Map<ParticipantKind, List<UUID>> byKind = new LinkedHashMap<>();
		for (Map.Entry<ParticipantKind, UUID> person : people) {
			byKind.computeIfAbsent(person.getKey(), (k) -> new ArrayList<>()).add(person.getValue());
		}
		Map<String, String> names = new HashMap<>();
		byKind.forEach((kind, ids) -> {
			List<UUID> distinct = ids.stream().distinct().toList();
			String sql = switch (kind) {
				case STAFF -> "SELECT id, display_name FROM team_member WHERE id IN (" + placeholders(distinct) + ")";
				case CLIENT -> "SELECT id, trim(coalesce(first_name, '') || ' ' || coalesce(last_name, '')) "
						+ "FROM client_account WHERE id IN (" + placeholders(distinct) + ")";
				case EXPERT -> "SELECT id, full_name FROM expert WHERE id IN (" + placeholders(distinct) + ")";
			};
			jdbc.query(sql, (rs) -> {
				String name = rs.getString(2);
				if (name == null || name.isBlank()) {
					name = kind == ParticipantKind.CLIENT ? "Client" : "EvalOS";
				}
				names.put(kind + ":" + rs.getObject(1, UUID.class), name);
			}, distinct.toArray());
		});
		return names;
	}

	/** What an inbox row shows about its case. */
	public record CaseContext(String caseCode, String serviceType, String stage) {
	}

	/** Case code, service and stage for these cases. Ids come from conversations already scoped. */
	public Map<UUID, CaseContext> caseContext(Collection<UUID> caseIds) {
		if (caseIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, CaseContext> context = new HashMap<>();
		List<UUID> distinct = caseIds.stream().distinct().toList();
		jdbc.query("SELECT id, case_code, service_type, current_stage FROM evalos_case WHERE id IN ("
				+ placeholders(distinct) + ")", (rs) -> {
					context.put(rs.getObject(1, UUID.class),
							new CaseContext(rs.getString(2), rs.getString(3), rs.getString(4)));
				}, distinct.toArray());
		return context;
	}

	private static String placeholders(Collection<?> values) {
		return String.join(", ", Collections.nCopies(values.size(), "?"));
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp edited = rs.getTimestamp("edited_at");
		Timestamp deleted = rs.getTimestamp("deleted_at");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
				ParticipantKind.valueOf(rs.getString("author_kind")), rs.getObject("author_id", UUID.class),
				rs.getString("body"), rs.getObject("parent_message_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(), edited == null ? null : edited.toInstant(),
				deleted == null ? null : deleted.toInstant(), rs.getInt("reply_count"));
	}
}
