package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Whether anything remembers a staff member — the Admin's delete asks before it deletes (2026-10-29).
 *
 * <p><strong>Read from the catalogue, not listed by hand.</strong> Every foreign key onto
 * {@code team_member} counts (cases, notes, payouts, meetings, pipeline grants, a manager line), so a
 * table added later counts without anyone remembering this class. The audit trail has no foreign key
 * by design (V10: it outlives the member), so the actions they performed are checked by name.
 */
@Repository
public class StaffHistoryRepository {

	private final JdbcTemplate jdbc;

	StaffHistoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean hasHistory(UUID memberId) {
		Boolean acted = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM audit_event WHERE actor_id = ?)",
				Boolean.class, memberId);
		if (Boolean.TRUE.equals(acted)) {
			return true;
		}
		// Identifiers come from pg_catalog and are quoted there, never from a caller.
		List<String> probes = jdbc.queryForList("""
				SELECT format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I = ?)', c.conrelid::regclass, a.attname)
				  FROM pg_constraint c
				  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
				 WHERE c.contype = 'f' AND c.confrelid = 'team_member'::regclass""", String.class);
		return probes.stream().anyMatch((sql) -> Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, memberId)));
	}
}
