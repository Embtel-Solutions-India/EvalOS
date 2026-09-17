package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which GHL pipelines a team member may work — Unit 44b's {@code team_member_pipeline}.
 *
 * <p><strong>JDBC, not Spring Data, and the reason is that there is no entity to map.</strong> The
 * table is a pure join: two foreign keys and two provenance columns, no identity of its own that
 * anything points at, no lifecycle, no scope axis. An entity for it would exist only so that a
 * {@code JpaRepository} had something to be generic over — and {@code DomainInvariantsTest} would
 * then demand a {@code SCOPE} declaration for a row whose scope <em>is</em> the member on one side
 * of it. Four statements of SQL is less code and says more.
 *
 * <p>Every read is keyed on the member or the pipeline, which is the same structural rule
 * {@code OpportunityRepositoryScopeTest} enforces next door: a finder with no key would return every
 * desk's assignments to whoever called it.
 */
@Repository
public class TeamMemberPipelineRepository {

	private final JdbcTemplate jdbc;

	TeamMemberPipelineRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The member's pipelines as <strong>GHL's ids</strong> — what a principal carries, and what
	 * {@code meeting}, {@code follow_up} and {@code opportunity_note} all store.
	 *
	 * <p>Joined through {@code pipeline} rather than returning the mirror's uuids, so the
	 * authorisation vocabulary stays the one every existing scoped table already speaks.
	 *
	 * <p><strong>Live pipelines only.</strong> One GHL has stopped returning cannot be worked, and
	 * leaving it in the set would let a desk write to something that is not there — which is
	 * {@code 00d} C4's failure in miniature.
	 */
	public List<String> ghlIdsFor(UUID memberId) {
		return jdbc.queryForList("""
				SELECT p.ghl_id FROM team_member_pipeline tmp
				  JOIN pipeline p ON p.id = tmp.pipeline_id
				 WHERE tmp.team_member_id = ? AND p.missing_since IS NULL
				 ORDER BY p.position, p.name""", String.class, memberId);
	}

	/** The mirror ids, for a caller that addresses the {@code pipeline} row itself. */
	public List<UUID> pipelineIdsFor(UUID memberId) {
		return jdbc.queryForList(
				"SELECT pipeline_id FROM team_member_pipeline WHERE team_member_id = ?",
				UUID.class, memberId);
	}

	/**
	 * Finishes Unit 44b's migration for members still described by the column it replaced.
	 *
	 * <p><strong>This exists because a seed cannot do it.</strong> {@code V54} backfills from
	 * {@code team_member.ghl_pipeline_id}, but on a fresh database it runs before the rows exist;
	 * {@code V910} is the same statement ordered after them, and it is <em>still</em> a no-op,
	 * because {@code pipeline} is filled by the PIPELINE_MIRROR sweep against real GHL rather than
	 * by any seed. A versioned seed runs once, at a moment when the join can never match — so every
	 * fresh database got zero desk assignments, permanently, and every desk signed in with no
	 * pipelines and drew an empty board.
	 *
	 * <p>Running it after each mirror pass is what makes it self-heal: the first pass that brings a
	 * pipeline in is the pass that can finally resolve the member pointing at it.
	 *
	 * <p><strong>The legacy column is a migration source here and nothing else.</strong> Nothing
	 * reads it for authorisation — {@code EvalOsUserDetailsService} fills a principal from this
	 * table — and a member assigned through the route needs none of it.
	 *
	 * @return how many assignments were created
	 */
	public int backfillFromLegacyColumn() {
		return jdbc.update("""
				INSERT INTO team_member_pipeline (team_member_id, pipeline_id)
				SELECT m.id, p.id
				  FROM team_member m
				  JOIN pipeline p ON p.ghl_id = m.ghl_pipeline_id AND p.brand_id = m.brand_id
				 WHERE m.ghl_pipeline_id IS NOT NULL AND p.missing_since IS NULL
				ON CONFLICT DO NOTHING""");
	}
	/** Who works this pipeline. <strong>May be empty</strong> — Case Delivery has no single owner. */
	public List<UUID> membersOn(UUID pipelineId) {
		return jdbc.queryForList(
				"SELECT team_member_id FROM team_member_pipeline WHERE pipeline_id = ?",
				UUID.class, pipelineId);
	}

	/**
	 * Puts a member on a pipeline.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} rather than a check-then-insert: a GM clicking twice is not
	 * a mistake worth a 400, and two concurrent grants must not turn into a constraint violation
	 * surfacing as a 500.
	 *
	 * @return 1 if this granted something, 0 if they already had it
	 */
	public int grant(UUID memberId, UUID pipelineId, UUID grantedBy) {
		return jdbc.update("""
				INSERT INTO team_member_pipeline (team_member_id, pipeline_id, granted_by)
				VALUES (?, ?, ?) ON CONFLICT DO NOTHING""", memberId, pipelineId, grantedBy);
	}

	/** @return 1 if this removed something, 0 if they were not on it */
	public int revoke(UUID memberId, UUID pipelineId) {
		return jdbc.update(
				"DELETE FROM team_member_pipeline WHERE team_member_id = ? AND pipeline_id = ?",
				memberId, pipelineId);
	}
}
