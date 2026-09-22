package com.ie.evalos.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The contacts list, in the three widths the roles read it at.
 *
 * <p><strong>JDBC rather than Spring Data, and the reason is the pipeline arm.</strong>
 * {@code ScopePredicate} builds every other scoped read, and it would build the first two of these
 * unchanged: {@code Tier.ALL} matches everything, {@code Tier.BRAND} matches the caller's brand.
 * It cannot build the third. A pipeline predicate needs a {@code pipeline} attribute <em>on the
 * row being read</em>, and a contact has no pipeline — a contact's <em>deals</em> do. Expressing
 * "contacts with at least one opportunity on one of my pipelines" needs a two-table join, and
 * {@code ScopePredicate.Fields} has no vocabulary for one. Adding that vocabulary would mean a
 * join axis in the class every scoped read in the system composes from, to serve one screen.
 *
 * <p><strong>The deals are reached through {@code pipeline}, not directly.</strong>
 * {@code opportunity} carries {@code pipeline_id}, the mirror's own uuid; a principal carries
 * GHL's ids. So the desk arm joins {@code opportunity -> pipeline} and matches on
 * {@code pipeline.ghl_id}, which is the same vocabulary {@code PipelineScope} and
 * {@code ScopePredicate}'s PIPELINE arm speak.
 *
 * <p><strong>The contact-to-deal join is brand-matched as well as id-matched.</strong>
 * {@code ghl_contact_id} is unique per brand and not globally, so joining on it alone would let
 * one brand's contact pick up another brand's deals — inflating a count, and on the GM's
 * cross-brand list doing it silently.
 *
 * <p><strong>Every scope is a fixed fragment; everything variable is a bind parameter.</strong>
 * The search term and the ids never touch the SQL string.
 */
@Repository
public class ContactDirectoryRepository {

	/**
	 * One row of the directory.
	 *
	 * @param dealCount      how many opportunities the mirror holds for this person, counted
	 *                       <em>within the caller's scope</em> — so a desk sees the number of
	 *                       deals they can actually open rather than a total they cannot account
	 *                       for
	 * @param lastActivityAt the newest {@code ghl_updated_at} across those deals, or null for a
	 *                       contact with none — a portal sign-up nobody has sold anything to yet
	 */
	public record Row(UUID id, UUID brandId, String brandName, String fullName, String email,
			String phone, String company, String sourceChannel, long dealCount,
			Instant lastActivityAt) {
	}

	/**
	 * One page, and how many rows there are behind it.
	 *
	 * <p><strong>{@code total} is the count of the whole scoped, searched set — not of this
	 * page.</strong> Without it the client cannot draw a last-page control or say "of 1,407", and
	 * a pager that only knows whether a next page exists makes a roster feel bottomless.
	 */
	public record Page(List<Row> rows, long total, int appliedSize) {
	}

	/**
	 * <strong>Offset paging, not keyset.</strong> A keyset cursor is strictly better for an
	 * infinite scroll and strictly worse for what this screen has: numbered pages the reader jumps
	 * around in. Offset is what lets page 7 be reachable without walking pages 1 to 6.
	 *
	 * <p>ponytail: {@code OFFSET} degrades on deep pages — the database still walks what it skips.
	 * Irrelevant at four figures of contacts; if this list ever reaches six, the fix is to keep
	 * offset for the numbered jumps and add a keyset cursor for the next/previous ones.
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private static final String SELECT = """
			SELECT c.id, c.brand_id, b.name AS brand_name, c.full_name, c.email, c.phone,
			       c.company, c.source_channel,
			       COUNT(o.id) AS deal_count,
			       MAX(o.ghl_updated_at) AS last_activity_at
			  FROM contact_snapshot c
			  JOIN brand b ON b.id = c.brand_id
			""";

	/** Left for the two brand-wide arms: a contact with no deal is still a contact. */
	private static final String LEFT_JOIN_DEALS = """
			  LEFT JOIN opportunity o
			         ON o.ghl_contact_id = c.ghl_contact_id AND o.brand_id = c.brand_id
			""";

	/** Inner for the desk arm: a contact with no deal on my pipelines is not mine to see. */
	private static final String JOIN_MY_DEALS = """
			  JOIN opportunity o
			    ON o.ghl_contact_id = c.ghl_contact_id AND o.brand_id = c.brand_id
			  JOIN pipeline p ON p.id = o.pipeline_id
			""";

	// Grouped by every selected contact column rather than by `c.id` alone. Postgres would accept
	// the shorthand, since id is the primary key and the rest are functionally dependent on it;
	// spelling them out is what keeps this readable and portable.
	private static final String GROUP_BY = """
			 GROUP BY c.id, c.brand_id, b.name, c.full_name, c.email, c.phone, c.company,
			          c.source_channel
			""";

	// `c.id` breaks the tie, so the order is TOTAL rather than merely sorted. Without it two
	// contacts of the same name could swap between page 1 and page 2 of the same read, and one of
	// them would appear twice while the other was never shown.
	private static final String ORDER_AND_PAGE = """
			 ORDER BY c.full_name ASC NULLS LAST, c.id ASC
			 LIMIT ? OFFSET ?
			""";

	private final JdbcTemplate jdbc;

	ContactDirectoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** {@code Tier.ALL} — the GM, across every brand. */
	public Page all(String search, int page, int size) {
		List<Object> args = new ArrayList<>();
		String fragment = search(search, args);
		String where = fragment.isEmpty() ? "" : " WHERE " + fragment.substring(" AND ".length());
		return page(LEFT_JOIN_DEALS, where, args, page, size);
	}

	/** {@code Tier.BRAND} — a Brand Manager, their own brand and no other. */
	public Page inBrand(UUID brandId, String search, int page, int size) {
		List<Object> args = new ArrayList<>();
		args.add(brandId);
		String where = " WHERE c.brand_id = ?" + search(search, args);
		return page(LEFT_JOIN_DEALS, where, args, page, size);
	}

	/**
	 * {@code Tier.PIPELINE} — a desk, the contacts on the deals they work.
	 *
	 * <p>Callers must not pass an empty set. {@code ContactDirectoryService} refuses that before it
	 * reaches here, for the same reason {@code ScopePredicate}'s PIPELINE arm returns a
	 * disjunction: {@code IN ()} is a syntax error in one dialect and a match-everything in
	 * another, and neither is the answer.
	 */
	public Page onPipelines(UUID brandId, Collection<String> ghlPipelineIds, String search,
			int page, int size) {
		if (ghlPipelineIds.isEmpty()) {
			throw new IllegalArgumentException("No pipelines: the caller should have been refused");
		}
		List<Object> args = new ArrayList<>();
		args.add(brandId);
		args.addAll(ghlPipelineIds);
		String placeholders = String.join(", ", Collections.nCopies(ghlPipelineIds.size(), "?"));
		String where = " WHERE c.brand_id = ? AND p.ghl_id IN (" + placeholders + ")"
				+ search(search, args);
		return page(JOIN_MY_DEALS, where, args, page, size);
	}

	/**
	 * One page of rows plus the total behind them, from one scope.
	 *
	 * <p><strong>Two statements, and the count is not derived from the page.</strong> The obvious
	 * saving — return {@code rows.size()} and let the client infer — is wrong on every page but the
	 * last, and wrong in the direction that makes a pager stop early.
	 *
	 * <p><strong>The count wraps the grouped query rather than repeating it as
	 * {@code COUNT(*)}.</strong> These queries group by contact, so a bare count over the join
	 * would count <em>deals</em> — a contact with three of them would be three rows, and the
	 * reader would be told the roster is bigger than it is.
	 */
	private Page page(String joins, String where, List<Object> scopeArgs, int page, int size) {
		int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int offset = Math.max(page, 0) * limit;

		Long total = jdbc.queryForObject(
				"SELECT count(*) FROM (" + SELECT + joins + where + GROUP_BY + ") counted",
				Long.class, scopeArgs.toArray());

		List<Object> pageArgs = new ArrayList<>(scopeArgs);
		pageArgs.add(limit);
		pageArgs.add(offset);
		List<Row> rows = jdbc.query(SELECT + joins + where + GROUP_BY + ORDER_AND_PAGE,
				ContactDirectoryRepository::map, pageArgs.toArray());

		// `limit`, not `rows.size()`. A last page of 7 out of 15 is still a page SIZE of 15, and a
		// client computing `ceil(total / size)` from the row count would compute the page count
		// from the smallest page it happened to fetch.
		return new Page(rows, total == null ? 0 : total, limit);
	}

	/**
	 * Name, email and company, case-insensitively.
	 *
	 * <p><strong>The phone is deliberately not searched.</strong> It is stored however GHL received
	 * it — spaced, bracketed, with or without a country code — so a substring match finds a number
	 * typed one way and misses the same number typed another. A search that works sometimes is
	 * worse than one that is not offered, because the reader concludes the contact is absent.
	 */
	private static String search(String search, List<Object> args) {
		if (search == null || search.isBlank()) {
			return "";
		}
		String like = "%" + search.strip().toLowerCase() + "%";
		args.add(like);
		args.add(like);
		args.add(like);
		return " AND (lower(c.full_name) LIKE ? OR lower(c.email) LIKE ? OR lower(c.company) LIKE ?)";
	}

	private static Row map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp lastActivity = rs.getTimestamp("last_activity_at");
		return new Row(
				rs.getObject("id", UUID.class),
				rs.getObject("brand_id", UUID.class),
				rs.getString("brand_name"),
				rs.getString("full_name"),
				rs.getString("email"),
				rs.getString("phone"),
				rs.getString("company"),
				rs.getString("source_channel"),
				rs.getLong("deal_count"),
				lastActivity == null ? null : lastActivity.toInstant());
	}
}
