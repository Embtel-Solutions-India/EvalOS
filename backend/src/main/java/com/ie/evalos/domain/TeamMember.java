package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A staff user: the authentication identity and the anchor for brand/team/self
 * scoping. Foreign keys ({@code brand_id}, {@code reports_to}) are held as raw
 * UUIDs, not associations — scoping is a plain column predicate and must never
 * depend on loading another entity.
 */
@Entity
@Table(name = "team_member")
public class TeamMember {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** NULL means all brands, which the DB allows for the GM only. */
	@Column(name = "brand_id")
	private UUID brandId;

	/** Groups a Project Manager's team. */
	@Column(name = "team_id")
	private UUID teamId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "reports_to")
	private UUID reportsTo;

	/**
	 * The one GHL pipeline a {@code SALES}/{@code MARKETING} member owns, and NULL for every
	 * other role — both directions enforced by {@code team_member_pipeline_matches_role}.
	 *
	 * <p>Held as the opaque GHL id, never the pipeline's name: this is an access key, and one
	 * that broke when somebody renamed a pipeline in GHL would lock an employee out of their own
	 * work.
	 */
	@Column(name = "ghl_pipeline_id")
	private String ghlPipelineId;

	/**
	 * Which kind of client this member handles. Display and reporting only — see {@link Segment},
	 * and note that nothing may branch on it.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "segment")
	private Segment segment;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected TeamMember() {
		// for JPA
	}

	public UUID getId() {
		return id;
	}

	public UUID getBrandId() {
		return brandId;
	}

	public UUID getTeamId() {
		return teamId;
	}

	public Role getRole() {
		return role;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public UUID getReportsTo() {
		return reportsTo;
	}

	public String getGhlPipelineId() {
		return ghlPipelineId;
	}

	public Segment getSegment() {
		return segment;
	}

	/**
	 * Assigns the GHL pipeline this member owns.
	 *
	 * <p>The only mutator on this entity, and it takes no null: clearing a pipeline would
	 * violate {@code team_member_pipeline_matches_role} on a pipeline-scoped role, and answering
	 * 500 from a constraint is how a UI acquires an error path nobody can test. Clearing only
	 * makes sense as part of a role change or a deactivation, which are different operations.
	 */
	public void assignPipeline(String ghlPipelineId) {
		if (ghlPipelineId == null || ghlPipelineId.isBlank()) {
			throw new IllegalArgumentException("A pipeline id is required; clearing is a role change or a deactivation");
		}
		this.ghlPipelineId = ghlPipelineId;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
