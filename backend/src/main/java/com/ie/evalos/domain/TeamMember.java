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

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
